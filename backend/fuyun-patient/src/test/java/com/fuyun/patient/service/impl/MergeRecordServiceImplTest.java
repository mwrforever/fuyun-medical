package com.fuyun.patient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.OngoingVisitQuery;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientMergedPayload;
import com.fuyun.patient.api.PatientSplitPayload;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.dto.MergeCreateRequest;
import com.fuyun.patient.entity.MergeRecord;
import com.fuyun.patient.entity.Patient;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.internal.PatientDomainEvent;
import com.fuyun.patient.mapper.MergeRecordMapper;
import com.fuyun.patient.service.IPatientIdentifierService;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.vo.MergeRecordVO;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 合并状态机单测（FU-M02-03/Spec §10）：SPI 放行与阻断、双人角色守卫、指针映射落库、
 * merged/split 事件载荷、拆分回挂与 REVERSED 终态、FAILED 重试、快照异常守卫、并发双批准
 * CAS 护航（EX-21：0 行重读定性 409/404、输家不重复执行合并序列）、拆分回挂批量面
 * （EX-38：快照标识键集一次批查+一次批量写、逐行读写零触达、空快照零触达）
 * （验收 IT 的单测基座；getById/save/updateById 以测试子类覆写承载，患者行走 Mockito IPatientService，
 * 审批抢锚 CAS 走 mock mapper 经 ReflectionTestUtils 注入 baseMapper——billing 同款先例）。
 */
@ExtendWith(MockitoExtension.class)
class MergeRecordServiceImplTest {

    @Mock
    private IPatientService patientService;

    @Mock
    private IPatientIdentifierService identifierService;

    @Mock
    private PatientCacheService cacheService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MergeRecordMapper mergeRecordMapper;

    /** 内存患者表（patientService.getById 替身数据源） */
    private final Map<Long, Patient> patients = new HashMap<>();

    /** 被测服务（默认 SPI 空清单=无注册实现） */
    private StubMergeService service;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（模块内既有 ServiceImpl 单测同款）：
        // split 的指针置空走 LambdaUpdateWrapper，需 Patient 列 lambda 缓存在位
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
    }

    @BeforeEach
    void setUp() {
        service = newService(new ObjectMapper(), List.of());
        // lenient：守卫类用例在触达依赖前即抛出，部分替身数据源在单用例内不消费
        lenient()
                .when(patientService.getById(any(Long.class)))
                .thenAnswer(inv -> patients.get(inv.getArgument(0, Long.class)));
        lenient().when(identifierService.listByPatient(any(Long.class))).thenReturn(List.of());
        // EX-21 审批 CAS 抢锚默认放行（返回 1=抢得执行权）：并发冲突用例在单测内重桩为 0；
        // lenient 同模块惯例——守卫类用例在触达 CAS 前即抛出，默认桩不消费不报错
        lenient()
                .when(mergeRecordMapper.casApproveProcessing(anyLong(), anyString()))
                .thenReturn(1);
    }

    /**
     * 装配被测服务并注入 CAS mapper 替身（容器外单测，ReflectionTestUtils 注入 baseMapper，
     * fuyun-billing RefundServiceImplTest 同款先例）。
     */
    private StubMergeService newService(ObjectMapper objectMapper, List<OngoingVisitQuery> spi) {
        StubMergeService svc = new StubMergeService(
                patientService, identifierService, cacheService, eventPublisher, objectMapper, spi);
        ReflectionTestUtils.setField(svc, "baseMapper", mergeRecordMapper);
        return svc;
    }

    /**
     * 测试替身：仅覆写 getById/save/updateById 三个 DB 触点（IService 继承方法的可测化），
     * 内存表承载合并记录读写；SPI 清单按用例注入。
     */
    static class StubMergeService extends MergeRecordServiceImpl {

        /** 内存合并记录表（id → 记录） */
        final Map<Long, MergeRecord> records = new HashMap<>();

        private long nextId = 1;

        StubMergeService(
                IPatientService patientService,
                IPatientIdentifierService identifierService,
                PatientCacheService cacheService,
                ApplicationEventPublisher eventPublisher,
                ObjectMapper objectMapper,
                List<OngoingVisitQuery> ongoingVisitQueries) {
            super(patientService, identifierService, cacheService, eventPublisher, objectMapper, ongoingVisitQueries);
        }

        @Override
        public MergeRecord getById(Serializable id) {
            return records.get(((Number) id).longValue());
        }

        @Override
        public boolean save(MergeRecord entity) {
            entity.setId(nextId++);
            records.put(entity.getId(), entity);
            return true;
        }

        @Override
        public boolean updateById(MergeRecord entity) {
            records.put(entity.getId(), entity);
            return true;
        }
    }

    /** 双档种子：1 号主档 NORMAL、2 号从档 NORMAL（含可补齐的 mobile/address 与姓名） */
    private Patient seedPatients() {
        Patient survivor = new Patient();
        survivor.setPatientId(1L);
        survivor.setStatus("NORMAL");
        Patient merged = seedMergedArchive();
        patients.put(1L, survivor);
        patients.put(2L, merged);
        return survivor;
    }

    /** 从档种子（含姓名/手机盲索引/住址密文——主档空字段补齐路径的载体） */
    private Patient seedMergedArchive() {
        Patient merged = new Patient();
        merged.setPatientId(2L);
        merged.setStatus("NORMAL");
        merged.setName("张三");
        merged.setMobileCipher("cipher-2");
        merged.setMobileHash("hash-2");
        merged.setAddressCipher("addr-2");
        return merged;
    }

    /** 经 create() 建立一条 PROCESSING 记录并回取内存实体（出参仅携 id，内存表为准） */
    private MergeRecord createRecord() {
        MergeRecordVO vo = service.create(new MergeCreateRequest(1L, 2L, "重复建档", null));
        return service.records.get(vo.getId());
    }

    @Test
    @DisplayName("SPI 无注册实现：warn 放行并完成合并（拍板 3 冻结语义）")
    void approveProceedsWhenSpiEmpty() {
        seedPatients();
        MergeRecord record = createRecord();
        MergeRecordVO vo = service.approve(record.getId(), "reviewer");
        assertThat(vo.getStatus()).isEqualTo("COMPLETED");
        assertThat(service.records.get(record.getId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(patients.get(2L).getStatus()).isEqualTo("MERGED");
        assertThat(patients.get(2L).getMergedIntoPatientId()).isEqualTo(1L);
        // 主档空字段补齐：mobile/address 从档非空而主档为空才落主档（原值在快照）
        assertThat(patients.get(1L).getMobileHash()).isEqualTo("hash-2");
        assertThat(patients.get(1L).getAddressCipher()).isEqualTo("addr-2");
        // 合并/拆分即时失效两档解析缓存（Cache-Aside 写路径顺序）
        verify(cacheService).evictView(1L);
        verify(cacheService).evictView(2L);
        ArgumentCaptor<PatientDomainEvent> captor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(PatientMessagingConstants.EVENT_MERGED);
        assertThat(((PatientMergedPayload) captor.getValue().payload()).survivorPatientId())
                .isEqualTo(1L);
        assertThat(((PatientMergedPayload) captor.getValue().payload()).mergedPatientId())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("SPI 注册实现命中在途就诊：PAT-1006 阻断且从档未变、记录未动")
    void approveBlockedByOngoingVisit() {
        // SPI 命中替身需在 createRecord 之前装配（内存表与替身实例绑定）
        service = newService(new ObjectMapper(), List.of(patientId -> true));
        seedPatients();
        MergeRecord record = createRecord();
        assertThatThrownBy(() -> service.approve(record.getId(), "reviewer"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_BLOCKED_BY_ONGOING_VISIT);
        assertThat(patients.get(2L).getStatus()).isEqualTo("NORMAL");
        assertThat(service.records.get(record.getId()).getStatus()).isEqualTo("PROCESSING");
        verify(eventPublisher, times(0)).publishEvent(any());
    }

    @Test
    @DisplayName("SPI 注册实现且双方无在途就诊：正常放行完成合并（注册后自动收紧、不误伤）")
    void approveProceedsWhenSpiReportsNoOngoingVisit() {
        service = newService(new ObjectMapper(), List.of(patientId -> false));
        seedPatients();
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        assertThat(service.records.get(record.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("双人角色：审批人与经办人相同 → PAT-1008 拒绝（IT 上下文缺省经办人为 system）")
    void approveBySameOperatorRejected() {
        seedPatients();
        MergeRecord record = createRecord();
        assertThatThrownBy(() -> service.approve(record.getId(), "system"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("拆分：COMPLETED→REVERSED 终态、从档恢复经显式 SET 清指针、发布 split 事件")
    void splitRestoresMergedArchiveAndPublishes() {
        seedPatients();
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        MergeRecordVO vo = service.split(record.getId(), "误合并纠正");
        assertThat(vo.getStatus()).isEqualTo("REVERSED");
        // 从档恢复走显式 SET（LambdaUpdateWrapper）：updateById 忽略 null 字段会遗留悬空合并指针（真栈 IT 实证）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Patient>> restoreCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(patientService).update(restoreCaptor.capture());
        assertThat(restoreCaptor.getValue().getSqlSet()).contains("status").contains("merged_into_patient_id");
        assertThat(restoreCaptor.getValue().getParamNameValuePairs().values())
                .as("合并指针必须显式置 NULL，而非不更新")
                .contains((Object) null);
        assertThat(service.records.get(record.getId()).getReversedAt()).isNotNull();
        assertThat(service.records.get(record.getId()).getReverseReason()).isEqualTo("误合并纠正");
        // approve(merged) + split(split) 共两次应用事件，第二次为 patient.patient.split（载荷：恢复档+主档）
        ArgumentCaptor<PatientDomainEvent> captor = ArgumentCaptor.forClass(PatientDomainEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        List<PatientDomainEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(PatientMessagingConstants.EVENT_MERGED);
        assertThat(events.get(1).eventType()).isEqualTo(PatientMessagingConstants.EVENT_SPLIT);
        PatientSplitPayload payload = (PatientSplitPayload) events.get(1).payload();
        assertThat(payload.restoredPatientId()).isEqualTo(2L);
        assertThat(payload.survivorPatientId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("拆分按快照回挂标识：从档标识改挂主档后拆分时按快照还原原挂接")
    void splitRehangsIdentifiersFromSnapshot() {
        seedPatients();
        PatientIdentifier identifier = new PatientIdentifier();
        identifier.setId(100L);
        identifier.setPatientId(2L);
        identifier.setIsPrimary(true);
        when(identifierService.listByPatient(2L)).thenReturn(List.of(identifier));
        // 桩面机械调整（EX-38）：拆分回挂从逐行 getById 改键集批查，该桩随实现迁移为 listByIds 批查桩
        when(identifierService.listByIds(List.of(100L))).thenReturn(List.of(identifier));
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        // 合并执行序列③：从档标识整批重挂主档
        assertThat(identifier.getPatientId()).isEqualTo(1L);
        verify(identifierService).updateById(identifier);
        // 拆分回挂：按快照 identifiers 清单还原原 patientId（从档）
        service.split(record.getId(), "误合并纠正");
        assertThat(identifier.getPatientId()).isEqualTo(2L);
    }

    /** 标识种子（id+挂接从档、主标识）——拆分回挂批量面用例的载体 */
    private PatientIdentifier identifierOf(long id) {
        PatientIdentifier identifier = new PatientIdentifier();
        identifier.setId(id);
        identifier.setPatientId(2L);
        identifier.setIsPrimary(true);
        return identifier;
    }

    @Test
    @DisplayName("拆分回挂批量面契约：快照标识恰一次 IN 键集批查+恰一次批量写，逐行 getById/updateById 零触达（EX-38）")
    void splitRehangsSnapshotIdentifiersInSingleKeysetQueryAndSingleBatchWrite() {
        seedPatients();
        PatientIdentifier first = identifierOf(101L);
        PatientIdentifier second = identifierOf(102L);
        when(identifierService.listByPatient(2L)).thenReturn(List.of(first, second, identifierOf(103L)));
        // 拆分时点 103 号行已被清理：键集批查只回存续两行，缺失行跳过不报错（原逐行 getById null 判容错等价）
        when(identifierService.listByIds(List.of(101L, 102L, 103L))).thenReturn(List.of(first, second));
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        // 清空合并阶段交互记录：拆分面单独锚定（合并③重挂属 approve 面，已由既有用例锚定）
        clearInvocations(identifierService);
        service.split(record.getId(), "误合并纠正");
        // 批查恰一次（EX-38 键集前置）：快照标识清单整批一次 IN 装载，替代逐行 getById 的 N+1
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> keysetCaptor = ArgumentCaptor.forClass(List.class);
        verify(identifierService, times(1)).listByIds(keysetCaptor.capture());
        assertThat(keysetCaptor.getValue()).containsExactly(101L, 102L, 103L);
        // 批量写恰一次：存续两行一次批量回挂从档，缺失行不进批量面
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PatientIdentifier>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(identifierService, times(1)).updateBatchById(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).extracting(PatientIdentifier::getId).containsExactly(101L, 102L);
        assertThat(batchCaptor.getValue())
                .allSatisfy(identifier -> assertThat(identifier.getPatientId()).isEqualTo(2L));
        // 拆分面逐行读写零触达（批量面收拢后的新契约，approve CAS 与合并③语义不在拆分面）
        verify(identifierService, never()).getById(any());
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
    }

    @Test
    @DisplayName("拆分空快照边界：无标识行时零批查零批量写（原空循环零触达语义保持，EX-38）")
    void splitWithEmptySnapshotMakesNoIdentifierCalls() {
        seedPatients();
        // 从档无标识（listByPatient 默认空桩）→ 合并快照 identifiers 为空数组
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        clearInvocations(identifierService);
        service.split(record.getId(), "误合并纠正");
        verify(identifierService, never()).listByIds(any());
        verify(identifierService, never()).updateBatchById(any());
        assertThat(service.records.get(record.getId()).getStatus()).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("拆分后再次合并可循环（M-1 部分唯一约束语义：REVERSED 不阻断新 PROCESSING）")
    void remergeAfterSplitSucceeds() {
        seedPatients();
        MergeRecord first = createRecord();
        service.approve(first.getId(), "reviewer");
        // 模拟 DB UPDATE 生效：split 的显式 SET 落到内存患者表（恢复 NORMAL 清指针），再合并才可发起
        when(patientService.update(any())).thenAnswer(inv -> {
            patients.get(2L).setStatus("NORMAL");
            patients.get(2L).setMergedIntoPatientId(null);
            return true;
        });
        service.split(first.getId(), "误合并纠正");
        MergeRecord second = createRecord();
        service.approve(second.getId(), "reviewer2");
        assertThat(service.records.get(second.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("FAILED 记录重试：approve 对 FAILED 重跑执行序列置回 COMPLETED（可重试语义）")
    void approveRetriesFailedRecord() {
        seedPatients();
        MergeRecord record = createRecord();
        record.setStatus("FAILED");
        service.approve(record.getId(), "reviewer");
        assertThat(service.records.get(record.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("发起合并：主档与从档同一档案 → PAT-1008 拒绝")
    void createRejectsSameArchive() {
        assertThatThrownBy(() -> service.create(new MergeCreateRequest(1L, 1L, "重复建档", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("发起合并：从档已是 MERGED → PAT-1003 拒绝")
    void createRejectsMergedArchive() {
        seedPatients();
        patients.get(2L).setStatus("MERGED");
        assertThatThrownBy(() -> service.create(new MergeCreateRequest(1L, 2L, "重复建档", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_ALREADY_MERGED);
    }

    @Test
    @DisplayName("发起合并：主档 FROZEN 不可作为主档 → PAT-1005 拒绝")
    void createRejectsFrozenSurvivor() {
        seedPatients();
        patients.get(1L).setStatus("FROZEN");
        assertThatThrownBy(() -> service.create(new MergeCreateRequest(1L, 2L, "重复建档", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("发起合并：任一方档案不存在 → PAT-1001 拒绝")
    void createRejectsMissingArchive() {
        assertThatThrownBy(() -> service.create(new MergeCreateRequest(1L, 2L, "重复建档", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.PATIENT_NOT_FOUND);
    }

    @Test
    @DisplayName("审批不存在的合并记录：PAT-1007 拒绝")
    void approveMissingRecordRejected() {
        assertThatThrownBy(() -> service.approve(404L, "reviewer"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("审批已终态记录（COMPLETED 再审批）：PAT-1008 拒绝")
    void approveTerminalRecordRejected() {
        seedPatients();
        MergeRecord record = createRecord();
        service.approve(record.getId(), "reviewer");
        assertThatThrownBy(() -> service.approve(record.getId(), "reviewer2"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("并发双批准护航：CAS 0 行重读定性 PAT-1008 拒绝，合并序列不重复执行（EX-21）")
    void approveLosesCasRaceRejectedWithoutReExecution() {
        seedPatients();
        MergeRecord record = createRecord();
        // 模拟并发赢家交错：审批人读快照（PROCESSING 过守卫）后、CAS 前另一事务已批准落库 COMPLETED
        when(mergeRecordMapper.casApproveProcessing(record.getId(), "reviewer")).thenAnswer(inv -> {
            service.records.get(record.getId()).setStatus("COMPLETED");
            return 0;
        });
        assertThatThrownBy(() -> service.approve(record.getId(), "reviewer"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.MERGE_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("并发");
                });
        // 输家不得重复执行合并序列：从档保持 NORMAL、标识未重挂、无 merged 事件
        assertThat(patients.get(2L).getStatus()).isEqualTo("NORMAL");
        verify(identifierService, never()).updateById(any(PatientIdentifier.class));
        verify(eventPublisher, times(0)).publishEvent(any());
    }

    @Test
    @DisplayName("并发双批准护航（输家重读行已消失）：PAT-1007 404 定性（与入口缺单语义一致禁漂移）")
    void approveCasZeroWithVanishedRecordClassifiedAsPat1007() {
        seedPatients();
        MergeRecord record = createRecord();
        when(mergeRecordMapper.casApproveProcessing(record.getId(), "reviewer")).thenAnswer(inv -> {
            service.records.remove(record.getId());
            return 0;
        });
        assertThatThrownBy(() -> service.approve(record.getId(), "reviewer"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PatientErrorCode.MERGE_RECORD_NOT_FOUND);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(eventPublisher, times(0)).publishEvent(any());
    }

    @Test
    @DisplayName("拆分非 COMPLETED 记录：PAT-1008 拒绝（仅已完成合并可拆分）")
    void splitNonCompletedRejected() {
        seedPatients();
        MergeRecord record = createRecord();
        assertThatThrownBy(() -> service.split(record.getId(), "误合并纠正"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(PatientErrorCode.MERGE_STATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("快照序列化失败：合并中止上抛 IllegalStateException（拆分回滚依据缺失即拒绝执行）")
    void snapshotSerializeFailureRejectsApprove() {
        seedPatients();
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        try {
            when(broken.writeValueAsString(any())).thenAnswer(inv -> {
                // Answer 可抛 checked 异常：注入序列化故障（拆分回滚依据缺失即拒绝合并）
                throw new JsonProcessingException("序列化故障注入") {};
            });
        } catch (JsonProcessingException e) {
            // mock 替身运行期不会真抛（默认返回 null），此处仅为受检异常的编译期收口
            throw new IllegalStateException(e);
        }
        // 故障序列化器需在 createRecord 之前装配（内存表与替身实例绑定）
        service = newService(broken, List.of());
        MergeRecord record = createRecord();
        assertThatThrownBy(() -> service.approve(record.getId(), "reviewer")).isInstanceOf(IllegalStateException.class);
        assertThat(patients.get(2L).getStatus()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("快照损坏：拆分解析失败上抛 IllegalStateException（数据损坏显式暴露）")
    void snapshotCorruptedRecordRejectsSplit() {
        seedPatients();
        MergeRecord record = createRecord();
        record.setStatus("COMPLETED");
        record.setPreSnapshot("not-json");
        assertThatThrownBy(() -> service.split(record.getId(), "误合并纠正")).isInstanceOf(IllegalStateException.class);
    }
}

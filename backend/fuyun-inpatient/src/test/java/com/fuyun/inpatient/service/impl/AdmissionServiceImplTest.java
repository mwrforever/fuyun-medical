package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.VisitAdmittedPayload;
import com.fuyun.inpatient.api.payload.VisitRegisteredPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.AdmissionCreateRequest;
import com.fuyun.inpatient.dto.AdmissionScheduleRequest;
import com.fuyun.inpatient.dto.VisitRegisterRequest;
import com.fuyun.inpatient.dto.WardAdmitRequest;
import com.fuyun.inpatient.entity.Admission;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.enums.AdmissionStatus;
import com.fuyun.inpatient.enums.BedStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.AdmissionMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.vo.AdmissionVO;
import com.fuyun.inpatient.vo.InpatientVisitVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 入院登记域服务单测（Task 3 七用例冻结集）：住院证登记入队与队列排序权重、登记确认同事务签发
 * I 型 visit_id（红线路径）、FROZEN 患者拦截（IP-1003）、visit_id 结构自检失败回滚、
 * 住院证作废两态 CAS、预约入院 CAS、入科确认 CAS 与 VisitAdmittedPayload 发布。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读
 * @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class AdmissionServiceImplTest {

    /** 住院证号（发号器桩固定返回值） */
    private static final String ADMISSION_NO = "AD2026092500001";

    /** I 型 14 位合法 visit_id（发号器桩固定返回值） */
    private static final String VISIT_ID = "I2026092500001";

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 住院证行固定 id（visit 落库关联载体） */
    private static final long ADMISSION_ID = 9001L;

    /** 入科目标床位 id */
    private static final long BED_ID = 555L;

    @Mock
    private AdmissionMapper admissionMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private PatientContextResolver patientContextResolver;

    @Mock
    private BedService bedService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<Admission> rowCaptor;

    @Captor
    private ArgumentCaptor<InpatientVisit> visitCaptor;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<Admission>> queryCaptor;

    private AdmissionServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（admission 三读面 + visit 定位/回读面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Admission.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdmissionServiceImpl(
                admissionMapper, visitMapper, seqGate, patientContextResolver, bedService, events);
        ReflectionTestUtils.setField(service, "baseMapper", admissionMapper);
        OperatorContextHolder.set("adm-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("住院证登记入队：发号 AD 号、归一主档落库、WAITING 态；队列查询按急诊优先>预约时段>候床时长排序")
    void createEnqueuesWaitingAdmissionAndQueueSortsByPriority() {
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(seqGate.nextNo("AD")).thenReturn(ADMISSION_NO);

        AdmissionVO vo = service.create(new AdmissionCreateRequest(
                PATIENT_ID,
                "OUTPATIENT",
                "O2026092500001",
                "D01",
                "W01",
                "EMERGENCY",
                LocalDate.of(2026, 9, 26),
                "肺部感染",
                "doc-01"));

        verify(admissionMapper).insert(rowCaptor.capture());
        Admission row = rowCaptor.getValue();
        assertThat(row.getStatus()).isEqualTo(AdmissionStatus.WAITING.getCode());
        assertThat(row.getAdmissionNo()).isEqualTo(ADMISSION_NO);
        // 归一主档落库（CF-3：从档输入收敛主档，业务数据禁绕过解析直连）
        assertThat(row.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(row.getIssuedDoctorId()).isEqualTo("doc-01");
        assertThat(vo.status()).isEqualTo(AdmissionStatus.WAITING.getCode());
        assertThat(vo.admissionNo()).isEqualTo(ADMISSION_NO);

        // 队列排序权重锚：急诊优先 > 预约时段（expect_date 升序、空值排后）> 候床时长（建行时间升序）
        Page<Admission> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(admissionMapper.selectPage(any(Page.class), any())).thenReturn(page);
        PageResult<AdmissionVO> queue = service.queue(AdmissionStatus.WAITING, 0, 20);
        assertThat(queue.total()).isEqualTo(1);
        assertThat(queue.content()).extracting(AdmissionVO::admissionNo).containsExactly(ADMISSION_NO);
        verify(admissionMapper).selectPage(any(Page.class), queryCaptor.capture());
        LambdaQueryWrapper<Admission> wrapper = rendered(queryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(AdmissionStatus.WAITING.getCode());
        assertThat(wrapper.getSqlSegment())
                .contains("ORDER BY CASE WHEN admission_type = 'EMERGENCY' THEN 0 ELSE 1 END")
                .contains("expect_date ASC NULLS LAST")
                .contains("created_at ASC");
    }

    @Test
    @DisplayName("登记确认同事务签发 visit_id：CAS 置 COMPLETED + I 型号落库 REGISTERED + 发布 VisitRegisteredPayload")
    void registerIssuesVisitIdInSameTransaction() {
        Admission admission = admissionRow(AdmissionStatus.WAITING);
        when(admissionMapper.selectOne(any())).thenReturn(admission);
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(admissionMapper.casComplete(ADMISSION_NO, "adm-01")).thenReturn(1);
        when(seqGate.nextVisitId()).thenReturn(VISIT_ID);

        InpatientVisitVO vo = service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI"));

        verify(admissionMapper).casComplete(ADMISSION_NO, "adm-01");
        verify(visitMapper).insert(visitCaptor.capture());
        InpatientVisit visitRow = visitCaptor.getValue();
        // 红线锚：visit_id 与归一 patient_id 同事务同时落库（M02 结论 ④），REGISTERED 态起算
        assertThat(visitRow.getVisitId()).isEqualTo(VISIT_ID);
        assertThat(visitRow.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(visitRow.getAdmissionId()).isEqualTo(ADMISSION_ID);
        assertThat(visitRow.getStatus()).isEqualTo(VisitStatus.REGISTERED.getCode());
        assertThat(visitRow.getInsuranceType()).isEqualTo("UEMI");
        assertThat(visitRow.getAdmissionDiagnosis()).isEqualTo("肺部感染");
        assertThat(visitRow.getRegisteredAt()).isNotNull();
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_REGISTERED);
        assertThat(event.payload())
                .isEqualTo(new VisitRegisteredPayload(
                        VISIT_ID,
                        PATIENT_ID,
                        ADMISSION_NO,
                        visitRow.getRegisteredAt().toInstant(),
                        "UEMI"));
        // GC26 锚：登记确认 CAS 限定候床/预约两态（终态/作废态由影响行数判定拒绝）
        String sql = recordSql(AdmissionMapper.class, "casComplete", String.class, String.class);
        assertThat(sql)
                .contains("status = 'COMPLETED'")
                .contains("WHERE admission_no = #{admissionNo}")
                .contains("status IN ('WAITING', 'SCHEDULED')")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("FROZEN 患者登记确认被拒：IP-1003，零 CAS 零落库零事件")
    void registerRejectsFrozenPatient() {
        when(admissionMapper.selectOne(any())).thenReturn(admissionRow(AdmissionStatus.WAITING));
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "FROZEN", true, "司法冻结"));

        assertThatThrownBy(() -> service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PATIENT_BLOCKED);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(admissionMapper, never()).casComplete(any(), any());
        verify(visitMapper, never()).insert(any(InpatientVisit.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("visit_id 结构自检失败即回滚：SeqGate 畸形值 I99 → IllegalStateException，visit 零落库零事件")
    void registerRollsBackOnMalformedVisitId() {
        when(admissionMapper.selectOne(any())).thenReturn(admissionRow(AdmissionStatus.SCHEDULED));
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(admissionMapper.casComplete(ADMISSION_NO, "adm-01")).thenReturn(1);
        when(seqGate.nextVisitId()).thenReturn("I99");

        assertThatThrownBy(() -> service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("I99");
        verify(visitMapper, never()).insert(any(InpatientVisit.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("住院证作废：床实态 RESERVED 时联动释放预占床位（联动②宽容判定），WAITING 作废不触达；终态再作废 IP-1002")
    void cancelMovesQueueStatesToCancelled() {
        Admission scheduled = admissionRow(AdmissionStatus.SCHEDULED);
        scheduled.setTargetBedId(BED_ID);
        when(admissionMapper.selectOne(any())).thenReturn(scheduled);
        when(admissionMapper.casCancel(ADMISSION_NO, "adm-01")).thenReturn(1);
        // 场景②：床实态 RESERVED（作废回读床行实态判定，仅预占态才 CAS 释放）
        when(bedService.bedStatus(BED_ID)).thenReturn(BedStatus.RESERVED.getCode());

        AdmissionVO vo = service.cancel(ADMISSION_NO);

        assertThat(vo.status()).isEqualTo(AdmissionStatus.CANCELLED.getCode());
        assertThat(vo.admissionNo()).isEqualTo(ADMISSION_NO);
        // 床位联动②锚：床实态预占时作废同事务联动释放（BedService.releaseForAdmission）
        verify(bedService).releaseForAdmission(BED_ID);
        // GC26 锚：作废 CAS 限定候床/预约两态 + 显式 deleted=0
        String sql = recordSql(AdmissionMapper.class, "casCancel", String.class, String.class);
        assertThat(sql)
                .contains("status = 'CANCELLED'")
                .contains("WHERE admission_no = #{admissionNo}")
                .contains("status IN ('WAITING', 'SCHEDULED')")
                .contains("deleted = 0");

        // 候床态作废（无预占床位）：零释放联动触达（清零前次调用后断言无新触达）
        Admission waiting = admissionRow(AdmissionStatus.WAITING);
        waiting.setTargetBedId(null);
        when(admissionMapper.selectOne(any())).thenReturn(waiting);
        clearInvocations(bedService);
        service.cancel(ADMISSION_NO);
        verifyNoInteractions(bedService);

        // 终态再作废：CAS 0 行定性 IP-1002（状态机违例）
        when(admissionMapper.selectOne(any())).thenReturn(scheduled);
        when(admissionMapper.casCancel(ADMISSION_NO, "adm-01")).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(ADMISSION_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("作废联动宽容语义：预占床已手工释放（FREE）warn 留痕放行作废；并发预约窗口以回读实态联动释放")
    void cancelToleratesReleasedBedAndConcurrentScheduleWindow() {
        // 场景①：SCHEDULED 证预占床已被登记台经手工释放入口置 FREE——作废回读床实态非预占，
        // 零释放触达放行作废（预占缺失不得阻断住院证终态落定）
        Admission scheduled = admissionRow(AdmissionStatus.SCHEDULED);
        scheduled.setTargetBedId(BED_ID);
        when(admissionMapper.selectOne(any())).thenReturn(scheduled);
        when(admissionMapper.casCancel(ADMISSION_NO, "adm-01")).thenReturn(1);
        when(bedService.bedStatus(BED_ID)).thenReturn(BedStatus.FREE.getCode());

        AdmissionVO vo = service.cancel(ADMISSION_NO);

        assertThat(vo.status()).isEqualTo(AdmissionStatus.CANCELLED.getCode());
        verify(bedService).bedStatus(BED_ID);
        verify(bedService, never()).releaseForAdmission(any());

        // 场景③（并发预约窗口）：CAS 前快照 WAITING（无床）、并发 schedule 已落 SCHEDULED+RESERVED——
        // 释放决策不依赖快照，作废 CAS 命中后回读住院证行权威 target_bed_id 仍联动释放
        Admission waitingSnapshot = admissionRow(AdmissionStatus.WAITING);
        Admission rescheduled = admissionRow(AdmissionStatus.SCHEDULED);
        rescheduled.setTargetBedId(BED_ID);
        when(admissionMapper.selectOne(any())).thenReturn(waitingSnapshot, rescheduled);
        when(bedService.bedStatus(BED_ID)).thenReturn(BedStatus.RESERVED.getCode());

        AdmissionVO concurrentVo = service.cancel(ADMISSION_NO);

        assertThat(concurrentVo.status()).isEqualTo(AdmissionStatus.CANCELLED.getCode());
        // 回读权威值构造出参：并发预约落定的目标床位在作废结果可见
        assertThat(concurrentVo.targetBedId()).isEqualTo(BED_ID);
        verify(bedService).releaseForAdmission(BED_ID);
    }

    @Test
    @DisplayName("预约入院：WAITING→SCHEDULED CAS 记录目标床位并联动预占（Task 4 联动①，无床预住院不联动）；非候床态 IP-1002")
    void scheduleMovesWaitingToScheduled() {
        when(admissionMapper.selectOne(any())).thenReturn(admissionRow(AdmissionStatus.WAITING));
        LocalDate expectDate = LocalDate.of(2026, 9, 26);
        when(admissionMapper.casSchedule(ADMISSION_NO, "W01", BED_ID, expectDate, "adm-01"))
                .thenReturn(1);

        AdmissionVO vo = service.schedule(ADMISSION_NO, new AdmissionScheduleRequest("W01", BED_ID, expectDate));

        assertThat(vo.status()).isEqualTo(AdmissionStatus.SCHEDULED.getCode());
        assertThat(vo.targetBedId()).isEqualTo(BED_ID);
        // 床位联动①锚：携目标床位即同事务联动预占（BedService.reserveForAdmission 置 RESERVED）
        verify(bedService).reserveForAdmission(BED_ID);
        // GC26 锚：预约 CAS 限定 WAITING 态 + 显式 deleted=0
        String sql = recordSql(
                AdmissionMapper.class,
                "casSchedule",
                String.class,
                String.class,
                Long.class,
                LocalDate.class,
                String.class);
        assertThat(sql)
                .contains("status = 'SCHEDULED'")
                .contains("expect_date = #{expectDate}")
                .contains("WHERE admission_no = #{admissionNo}")
                .contains("status = 'WAITING'")
                .contains("deleted = 0");

        // 预住院模式（无床虚拟登记）：目标床位缺席零预占联动（强范式：清零后断言零触达）
        when(admissionMapper.casSchedule(ADMISSION_NO, "W01", null, expectDate, "adm-01"))
                .thenReturn(1);
        clearInvocations(bedService);
        service.schedule(ADMISSION_NO, new AdmissionScheduleRequest("W01", null, expectDate));
        verifyNoInteractions(bedService);

        // 已预约再预约：CAS 0 行定性 IP-1002
        when(admissionMapper.casSchedule(ADMISSION_NO, "W01", BED_ID, expectDate, "adm-01"))
                .thenReturn(0);
        assertThatThrownBy(
                        () -> service.schedule(ADMISSION_NO, new AdmissionScheduleRequest("W01", BED_ID, expectDate)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("入科确认：visit REGISTERED→ADMITTED CAS + 床位占床联动（Task 4 联动③）+ 发布 VisitAdmittedPayload")
    void admitWardMarksVisitAdmittedAndPublishesEvent() {
        InpatientVisit admitted = visitRow(VisitStatus.ADMITTED);
        admitted.setCurrentWardId("W01");
        admitted.setCurrentBedId(BED_ID);
        admitted.setNursingLevel("NORMAL");
        admitted.setAdmittedAt(OffsetDateTime.now());
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.REGISTERED), admitted);
        when(visitMapper.casAdmitWard(VISIT_ID, "D01", "W01", BED_ID, "doc-02", "NORMAL", "adm-01"))
                .thenReturn(1);

        InpatientVisitVO vo =
                service.admitWard(VISIT_ID, new WardAdmitRequest("D01", "W01", BED_ID, "NORMAL", "doc-02"));

        assertThat(vo.status()).isEqualTo(VisitStatus.ADMITTED.getCode());
        assertThat(vo.currentBedId()).isEqualTo(BED_ID);
        // 床位联动③锚：床位 RESERVED→OCCUPIED + bed_assign 开 ADMISSION 流水（BedService 同源 CAS）
        verify(bedService).occupyForAdmission(BED_ID, VISIT_ID, PATIENT_ID);
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_ADMITTED);
        assertThat(event.payload())
                .isEqualTo(new VisitAdmittedPayload(
                        VISIT_ID,
                        PATIENT_ID,
                        "W01",
                        BED_ID,
                        admitted.getAdmittedAt().toInstant(),
                        "NORMAL"));
        // GC26 锚：入科 CAS 限定 REGISTERED 态、入科时点库端 now()（禁应用时钟）
        String sql = recordSql(
                InpatientVisitMapper.class,
                "casAdmitWard",
                String.class,
                String.class,
                String.class,
                Long.class,
                String.class,
                String.class,
                String.class);
        assertThat(sql)
                .contains("status = 'ADMITTED'")
                .contains("admitted_at = now()")
                .contains("WHERE visit_id = #{visitId}")
                .contains("status = 'REGISTERED'")
                .contains("deleted = 0");

        // visit 无命中定性 IP-1007
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() ->
                        service.admitWard(VISIT_ID, new WardAdmitRequest("D01", "W01", BED_ID, "NORMAL", "doc-02")))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("入科状态违例与回读缺失：非 REGISTERED 态 CAS 0 行 IP-1008；CAS 后行被并发逻辑删 IP-1023")
    void admitWardRejectsStateViolationAndMissingReadback() {
        // 护理级别词表外（服务面校验覆盖模块内直调场景；Web 层由 @Pattern 兜底）
        assertThatThrownBy(
                        () -> service.admitWard(VISIT_ID, new WardAdmitRequest("D01", "W01", BED_ID, "HIGH", "doc-02")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(visitMapper, events);

        // 非 REGISTERED 态（已入科/已出院/已作废）：CAS 0 行定性 IP-1008
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        when(visitMapper.casAdmitWard(VISIT_ID, "D01", "W01", BED_ID, "doc-02", "NORMAL", "adm-01"))
                .thenReturn(0);
        assertThatThrownBy(() ->
                        service.admitWard(VISIT_ID, new WardAdmitRequest("D01", "W01", BED_ID, "NORMAL", "doc-02")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED));
        verifyNoInteractions(events);

        // CAS 命中后回读缺失（并发逻辑删）：定性 IP-1023
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.REGISTERED), null);
        when(visitMapper.casAdmitWard(VISIT_ID, "D01", "W01", BED_ID, "doc-02", "NORMAL", "adm-01"))
                .thenReturn(1);
        assertThatThrownBy(() ->
                        service.admitWard(VISIT_ID, new WardAdmitRequest("D01", "W01", BED_ID, "NORMAL", "doc-02")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("建单守卫链：来源/类型词表外与转诊缺门诊 visit_id 引用均拒 IP-1022，零落库零发号")
    void createRejectsInvalidVocabularyAndMissingReferral() {
        // 来源词表外（服务面校验覆盖模块内直调场景；Web 层由 @Pattern 兜底）
        assertThatThrownBy(() -> service.create(new AdmissionCreateRequest(
                        PATIENT_ID, "REFERRAL", null, null, null, "NORMAL", null, null, "doc-01")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        // 入院类型词表外
        assertThatThrownBy(() -> service.create(new AdmissionCreateRequest(
                        PATIENT_ID, "OUTPATIENT", "O2026092500001", null, null, "URGENT", null, null, "doc-01")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        // 门诊转诊来源缺门诊 visit_id 引用（M03 转诊关联必携）
        assertThatThrownBy(() -> service.create(new AdmissionCreateRequest(
                        PATIENT_ID, "OUTPATIENT", null, null, null, "NORMAL", null, null, "doc-01")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage()).contains("sourceVisitId"));
        verifyNoInteractions(seqGate, events);
        verify(admissionMapper, never()).insert(any(Admission.class));
    }

    @Test
    @DisplayName("建单档案冻结拒绝与证号唯一冲突：IP-1003 前置拦截；DuplicateKeyException 转 IP-1023")
    void createRejectsFrozenPatientAndTranslatesAdmissionNoConflict() {
        // FROZEN 建单即拒（新就诊拦截在建单面先行，不必等到登记确认）
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "FROZEN", true, "司法冻结"));
        assertThatThrownBy(() -> service.create(new AdmissionCreateRequest(
                        PATIENT_ID, "EMERGENCY", null, null, null, "EMERGENCY", null, null, "doc-01")))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PATIENT_BLOCKED));
        verify(admissionMapper, never()).insert(any(Admission.class));

        // 证号唯一冲突（uk_admission_no 兜底）：DuplicateKeyException 转业务拒绝 IP-1023
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(seqGate.nextNo("AD")).thenReturn(ADMISSION_NO);
        when(admissionMapper.insert(any(Admission.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.create(new AdmissionCreateRequest(
                        PATIENT_ID, "EMERGENCY", null, null, null, "EMERGENCY", null, null, "doc-01")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("登记确认边界：证不存在 IP-1001；终态再确认 IP-1002；visit_id 唯一冲突转 IP-1023 回滚")
    void registerRejectsMissingAdmissionAndTranslatesVisitIdConflict() {
        // 住院证无命中定性 IP-1001
        when(admissionMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_NOT_FOUND));

        // 终态证（COMPLETED）再确认：CAS 0 行定性 IP-1002，零签发零落库
        when(admissionMapper.selectOne(any())).thenReturn(admissionRow(AdmissionStatus.COMPLETED));
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(admissionMapper.casComplete(ADMISSION_NO, "adm-01")).thenReturn(0);
        assertThatThrownBy(() -> service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_STATE_NOT_ALLOWED));
        verify(visitMapper, never()).insert(any(InpatientVisit.class));
        verifyNoInteractions(seqGate, events);

        // visit_id 唯一冲突（发号器异常回绕极端并发）：DuplicateKeyException 转业务拒绝
        when(admissionMapper.selectOne(any())).thenReturn(admissionRow(AdmissionStatus.WAITING));
        when(admissionMapper.casComplete(ADMISSION_NO, "adm-01")).thenReturn(1);
        when(seqGate.nextVisitId()).thenReturn(VISIT_ID);
        when(visitMapper.insert(any(InpatientVisit.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.register(ADMISSION_NO, new VisitRegisterRequest("UEMI")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("作废/预约边界：住院证不存在一律 IP-1001，零 CAS 触达")
    void scheduleAndCancelRejectMissingAdmission() {
        when(admissionMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(ADMISSION_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_NOT_FOUND));
        assertThatThrownBy(() -> service.schedule(
                        ADMISSION_NO, new AdmissionScheduleRequest("W01", BED_ID, LocalDate.of(2026, 9, 26))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ADMISSION_NOT_FOUND));
        verify(admissionMapper, never()).casCancel(any(), any());
        verify(admissionMapper, never()).casSchedule(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("免登录上下文回退：无操作者时审计列落 system（与 V902 列默认同源）")
    void createFallsBackToSystemOperatorWithoutContext() {
        OperatorContextHolder.clear();
        when(patientContextResolver.resolve(PATIENT_ID))
                .thenReturn(new PatientContextView(PATIENT_ID, PATIENT_ID, "NORMAL", false, ""));
        when(seqGate.nextNo("AD")).thenReturn(ADMISSION_NO);

        service.create(
                new AdmissionCreateRequest(PATIENT_ID, "EMERGENCY", null, null, null, "NORMAL", null, null, "doc-01"));

        verify(admissionMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getCreatedBy()).isEqualTo("system");
        assertThat(rowCaptor.getValue().getUpdatedBy()).isEqualTo("system");
    }

    /** 构造住院证行（状态可变，登记确认/作废/预约用例载体）。 */
    private Admission admissionRow(AdmissionStatus status) {
        Admission row = new Admission();
        row.setId(ADMISSION_ID);
        row.setAdmissionNo(ADMISSION_NO);
        row.setPatientId(PATIENT_ID);
        row.setAdmissionType("EMERGENCY");
        row.setDiagnosisSummary("肺部感染");
        row.setStatus(status.getCode());
        return row;
    }

    /** 构造住院就诊行（状态可变，入科确认用例载体）。 */
    private InpatientVisit visitRow(VisitStatus status) {
        InpatientVisit row = new InpatientVisit();
        row.setId(7001L);
        row.setAdmissionId(ADMISSION_ID);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setInsuranceType("UEMI");
        row.setRegisteredAt(OffsetDateTime.now());
        row.setStatus(status.getCode());
        return row;
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Admission> rendered(Wrapper<Admission> captured) {
        LambdaQueryWrapper<Admission> wrapper = (LambdaQueryWrapper<Admission>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param mapper     mapper 接口类型
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String recordSql(Class<?> mapper, String method, Class<?>... paramTypes) {
        try {
            Update update = mapper.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}

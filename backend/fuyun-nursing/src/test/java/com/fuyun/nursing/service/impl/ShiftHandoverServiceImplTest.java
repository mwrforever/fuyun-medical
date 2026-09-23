package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.ShiftCompletedPayload;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.HandoverCompleteRequest;
import com.fuyun.nursing.dto.HandoverGenerateRequest;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.entity.ShiftHandover;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.ShiftHandoverMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.ShiftHandoverVO;
import com.fuyun.nursing.vo.WardConfigVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * 交接班域服务单测（Task 9 十用例冻结集 + 补充覆盖锚）：自动汇总患者摘要（在区总数/护理级别分布/
 * 病情标记计数）、逐患者在途任务待续事项、在途输注与未闭环告警 P1 空数组锚、SBAR 初稿文本拼装、
 * 交班签名与 DRAFT 态、未知病区拒绝、完成 CAS 双签与 nursing.shift.completed 载荷逐字断言、
 * 空串 SBAR 保留初稿、重复完成拒绝、清单按日过滤与班次升序。MP 3.5.17 单测范式：lambdaQuery
 * 触达实体 @BeforeAll 手工注册表信息；条件更新断言直读 @Update 注解 SQL（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class ShiftHandoverServiceImplTest {

    /** 病区编码（交接班所属病区） */
    private static final String WARD = "W01";

    /** 班次 code（V801 种子三班制白班） */
    private static final String SHIFT = "DAY";

    /** 交接班单号（发号器桩固定返回值，HO+yyyyMMdd+5 位流水） */
    private static final String HANDOVER_NO = "HO2026092200001";

    /** 接班护士工号（完成用例固定值） */
    private static final String INCOMING = "9";

    /** 患者甲就诊号（I 型 14 位，在区视图替身） */
    private static final String VISIT_A = "I2026092200001";

    /** 患者乙就诊号 */
    private static final String VISIT_B = "I2026092200002";

    /** 患者丙就诊号 */
    private static final String VISIT_C = "I2026092200003";

    /** 患者丁就诊号 */
    private static final String VISIT_D = "I2026092200004";

    /** 交接班行固定 id（insert 桩回填值） */
    private static final long ROW_ID = 701L;

    @Mock
    private ShiftHandoverMapper handoverMapper;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    @Mock
    private IWardMetaService wardMetaService;

    @Mock
    private INursingTaskService taskService;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<ShiftHandover> rowCaptor;

    @Captor
    private ArgumentCaptor<NursingDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<NursingWardPatient>> wardQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<ShiftHandover>> handoverQueryCaptor;

    private ShiftHandoverServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息（病区视图读 + 交接班清单读）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingWardPatient.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShiftHandover.class);
    }

    @BeforeEach
    void setUp() {
        // JavaTimeModule 注册 + ISO 时间文本口径与 Boot 自动装配 ObjectMapper 一致（planTime 序列化往返）
        service = new ShiftHandoverServiceImpl(
                handoverMapper,
                wardPatientMapper,
                wardMetaService,
                taskService,
                seqGate,
                events,
                new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        ReflectionTestUtils.setField(service, "baseMapper", handoverMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("自动汇总患者摘要：在区 4 人（2 普通/1 病重/1 特级，2 新入/1 手术）逐计数落快照")
    void generateAggregatesPatientSummaryFromWardView() {
        stubGenerateBaseline(fourPatients());
        stubInsertOk();

        ShiftHandoverVO vo = service.generate(new HandoverGenerateRequest(WARD, SHIFT));

        // 摘要计数锚：总数/护理级别分布（SPECIAL/CRITICAL）/病情标记计数（NEW/SURGERY）
        assertThat(vo.patientSummary().total()).isEqualTo(4);
        assertThat(vo.patientSummary().specialCount()).isEqualTo(1);
        assertThat(vo.patientSummary().criticalCount()).isEqualTo(1);
        assertThat(vo.patientSummary().newAdmissionCount()).isEqualTo(2);
        assertThat(vo.patientSummary().surgeryCount()).isEqualTo(1);
        // 汇总数据源谓词锚：病区 + 在区态（listByWard 同口径，床位序）
        verify(wardPatientMapper).selectList(wardQueryCaptor.capture());
        LambdaQueryWrapper<NursingWardPatient> wrapper = rendered(wardQueryCaptor.getValue());
        assertThat(wrapper.getParamNameValuePairs().values()).contains(WARD, WardPatientStatus.IN_WARD.getCode());
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY").contains("bed_no");
    }

    @Test
    @DisplayName("待续事项逐患者收集：在途任务非空患者入选（含 taskNo/taskType/planTime/overdueFlag），空患者不入选")
    void generateCollectsInFlightTasksPerPatient() {
        // UTC 口径构造（JSONB 序列化往返偏移归一至 mapper 上下文时区，等值断言钉 UTC）
        OffsetDateTime planTime = LocalDate.of(2026, 9, 22).atTime(8, 0).atOffset(ZoneOffset.UTC);
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfig());
        when(wardPatientMapper.selectList(any()))
                .thenReturn(List.of(wardRow("01", "NORMAL", "NEW", VISIT_A), wardRow("02", "NORMAL", "", VISIT_B)));
        // 患者甲 2 条在途、患者乙 0 条：待续事项仅含甲的 2 条
        when(taskService.inFlightByVisit(VISIT_A))
                .thenReturn(List.of(
                        taskVO("TK2026092200001", TaskType.MEDICATION.getCode(), planTime, true),
                        taskVO("TK2026092200002", TaskType.TURN.getCode(), planTime.plusHours(2), false)));
        when(taskService.inFlightByVisit(VISIT_B)).thenReturn(List.of());
        when(seqGate.nextNo("HO")).thenReturn(HANDOVER_NO);
        when(handoverMapper.insert(any(ShiftHandover.class))).thenAnswer(insertWithId(ROW_ID));

        ShiftHandoverVO vo = service.generate(new HandoverGenerateRequest(WARD, SHIFT));

        assertThat(vo.pendingItems()).hasSize(2);
        assertThat(vo.pendingItems())
                .extracting(ShiftHandoverVO.PendingItem::taskNo)
                .containsExactly("TK2026092200001", "TK2026092200002");
        // 字段完整锚：taskType/planTime（序列化往返等值）/overdueFlag/visitId 均随待续事项透出
        assertThat(vo.pendingItems())
                .extracting(ShiftHandoverVO.PendingItem::taskType)
                .containsExactly(TaskType.MEDICATION.getCode(), TaskType.TURN.getCode());
        assertThat(vo.pendingItems())
                .extracting(ShiftHandoverVO.PendingItem::planTime)
                .containsExactly(planTime, planTime.plusHours(2));
        assertThat(vo.pendingItems())
                .extracting(ShiftHandoverVO.PendingItem::overdueFlag)
                .containsExactly(true, false);
        assertThat(vo.pendingItems())
                .extracting(ShiftHandoverVO.PendingItem::visitId)
                .containsOnly(VISIT_A);
    }

    @Test
    @DisplayName("P1 空数组锚：在途输注与未闭环告警恒为空数组（M14/M16 缺位，P2 接入后本断言随 Spec 注记扩展）")
    void generateLeavesInfusionAndAlarmPlaceholdersEmptyInP1() {
        stubGenerateBaseline(List.of(wardRow("01", "NORMAL", "", VISIT_A)));
        stubInsertOk();

        ShiftHandoverVO vo = service.generate(new HandoverGenerateRequest(WARD, SHIFT));

        assertThat(vo.pendingInfusions()).isEmpty();
        assertThat(vo.unclosedAlarms()).isEmpty();
        // 落库面同锚：两占位列写死 '[]'（P1 空数组，P2 接入 M14/M16 后替换）
        verify(handoverMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getPendingInfusions()).isEqualTo("[]");
        assertThat(rowCaptor.getValue().getUnclosedAlarms()).isEqualTo("[]");
    }

    @Test
    @DisplayName("SBAR 初稿拼装：S 含在区计数与危重计数及床位列表，B 含新入/手术计数，A/R 非空模板文本")
    void generateBuildsSbarDraftFromSummary() {
        stubGenerateBaseline(fourPatients());
        stubInsertOk();

        ShiftHandoverVO vo = service.generate(new HandoverGenerateRequest(WARD, SHIFT));

        // S 现状：在区总数 + 特级/病重计数 + 危重患者床位号列表（床位序 03/04）
        assertThat(vo.sbarSituation())
                .contains("在区 4 人")
                .contains("特级护理 1 人")
                .contains("病重护理 1 人")
                .contains("03")
                .contains("04");
        // B 背景：新入/手术/今日出院/转出计数
        assertThat(vo.sbarBackground()).contains("新入 2 人").contains("手术 1 人");
        // A 评估 / R 建议：初稿模板文本非空（人工补充确认的基线）
        assertThat(vo.sbarAssessment()).isNotBlank();
        assertThat(vo.sbarRecommendation()).isNotBlank();
    }

    @Test
    @DisplayName("交班签名与草稿态：生成即盖章 outgoingSignedAt、outgoingNurseId=当前操作者，status=DRAFT")
    void generateStampsOutgoingSignatureAndDraftStatus() {
        OffsetDateTime before = OffsetDateTime.now();
        stubGenerateBaseline(List.of(wardRow("01", "NORMAL", "", VISIT_A)));
        stubInsertOk();

        ShiftHandoverVO vo = service.generate(new HandoverGenerateRequest(WARD, SHIFT));

        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.outgoingSignedAt()).isNotNull();
        assertThat(vo.outgoingNurseId()).isEqualTo("nurse-01");
        // 落库面锚：单号/病区/班次/交接日期/审计列同刻装配
        verify(handoverMapper).insert(rowCaptor.capture());
        ShiftHandover row = rowCaptor.getValue();
        assertThat(row.getHandoverNo()).isEqualTo(HANDOVER_NO);
        assertThat(row.getWardId()).isEqualTo(WARD);
        assertThat(row.getShiftCode()).isEqualTo(SHIFT);
        assertThat(row.getHandoverDate()).isEqualTo(LocalDate.now());
        assertThat(row.getStatus()).isEqualTo("DRAFT");
        assertThat(row.getOutgoingNurseId()).isEqualTo("nurse-01");
        assertThat(row.getOutgoingSignedAt()).isCloseTo(before, within(2, ChronoUnit.SECONDS));
        assertThat(row.getCreatedBy()).isEqualTo("nurse-01");
    }

    @Test
    @DisplayName("未知病区拒绝：病区无配置行（wardConfig 实况 NS-1016）上抛，不查视图不发号不落库")
    void generateRejectsUnknownWard() {
        // Task 3 冻结面实况回放：无配置行 → NS-1016（未知病区归资源冲突语义，与 NS-1019 入参格式严格分开）
        when(wardMetaService.wardConfig("W99"))
                .thenThrow(new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "未知病区或缺少护理配置：wardId=W99"));

        assertThatThrownBy(() -> service.generate(new HandoverGenerateRequest("W99", SHIFT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("未知病区");
                });
        verify(wardPatientMapper, never()).selectList(any());
        verifyNoInteractions(seqGate, taskService, events);
        verify(handoverMapper, never()).insert(any(ShiftHandover.class));
    }

    @Test
    @DisplayName("完成双签与事件：CAS 置 COMPLETED、incomingSignedAt 盖章、nursing.shift.completed 载荷逐字断言")
    void completeStampsIncomingSignatureAndPublishesEvent() {
        when(handoverMapper.casComplete(HANDOVER_NO, INCOMING, "", "", "", "", "nurse-01"))
                .thenReturn(1);
        when(handoverMapper.selectOne(any())).thenReturn(completedRow());

        ShiftHandoverVO vo =
                service.complete(HANDOVER_NO, new HandoverCompleteRequest(INCOMING, null, null, null, null));

        assertThat(vo.status()).isEqualTo("COMPLETED");
        assertThat(vo.incomingSignedAt()).isNotNull();
        assertThat(vo.incomingNurseId()).isEqualTo(INCOMING);
        // 完成事件（事务内发布 AFTER_COMMIT 出站 GC8；M19 工作量统计消费）：五字段逐字断言
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_SHIFT_COMPLETED);
        ShiftCompletedPayload payload = (ShiftCompletedPayload) event.payload();
        assertThat(payload.handoverNo()).isEqualTo(HANDOVER_NO);
        assertThat(payload.wardId()).isEqualTo(WARD);
        assertThat(payload.shiftCode()).isEqualTo(SHIFT);
        assertThat(payload.outgoingNurseId()).isEqualTo("nurse-01");
        assertThat(payload.incomingNurseId()).isEqualTo(INCOMING);
        // GC26 可执行锚：完成必须为 @Update 注解 SQL 条件更新（双签态迁移 + 空串保留初稿 + deleted=0）
        String sql = recordSql(
                "casComplete",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class);
        assertThat(sql)
                .contains("status = 'COMPLETED'")
                .contains("incoming_nurse_id = #{incomingNurseId}")
                .contains("incoming_signed_at = now()")
                .contains("COALESCE(NULLIF(#{sbarSituation}, ''), sbar_situation)")
                .contains("COALESCE(NULLIF(#{sbarBackground}, ''), sbar_background)")
                .contains("COALESCE(NULLIF(#{sbarAssessment}, ''), sbar_assessment)")
                .contains("COALESCE(NULLIF(#{sbarRecommendation}, ''), sbar_recommendation)")
                .contains("WHERE handover_no = #{handoverNo}")
                .contains("status IN ('DRAFT', 'SIGNING')")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("空串 SBAR 保留初稿：complete 四段传空串 → 落库值仍为生成初稿文本（不被覆盖）")
    void completeKeepsDraftSbarWhenRequestBlank() {
        stubGenerateBaseline(fourPatients());
        stubInsertOk();
        ShiftHandoverVO draft = service.generate(new HandoverGenerateRequest(WARD, SHIFT));
        // 捕获生成落库行并改造为完成后替身：CAS 的 COALESCE(NULLIF('', '')) 语义回放——
        // 空串不覆盖初稿，仅双签字段落定
        verify(handoverMapper).insert(rowCaptor.capture());
        ShiftHandover completed = rowCaptor.getValue();
        completed.setIncomingNurseId(INCOMING);
        completed.setIncomingSignedAt(OffsetDateTime.now());
        completed.setStatus("COMPLETED");
        when(handoverMapper.casComplete(HANDOVER_NO, INCOMING, "", "", "", "", "nurse-01"))
                .thenReturn(1);
        when(handoverMapper.selectOne(any())).thenReturn(completed);

        ShiftHandoverVO vo = service.complete(HANDOVER_NO, new HandoverCompleteRequest(INCOMING, "", "", "", ""));

        assertThat(vo.sbarSituation()).isEqualTo(draft.sbarSituation());
        assertThat(vo.sbarBackground()).isEqualTo(draft.sbarBackground());
        assertThat(vo.sbarAssessment()).isEqualTo(draft.sbarAssessment());
        assertThat(vo.sbarRecommendation()).isEqualTo(draft.sbarRecommendation());
        assertThat(vo.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("重复完成拒绝：CAS 0 行（不存在或已 COMPLETED）拒 NS-1013，零回读零事件")
    void completeRejectsAlreadyCompleted() {
        when(handoverMapper.casComplete(HANDOVER_NO, INCOMING, "", "", "", "", "nurse-01"))
                .thenReturn(0);

        assertThatThrownBy(() ->
                        service.complete(HANDOVER_NO, new HandoverCompleteRequest(INCOMING, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.HANDOVER_STATE_NOT_ALLOWED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1013");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(handoverMapper, never()).selectOne(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("清单按日过滤与班次升序：跨日行由 handover_date 谓词滤除，同日按 shift_code 升序")
    void listByWardFiltersByDateAndOrdersByShift() {
        LocalDate day = LocalDate.of(2026, 9, 22);
        ShiftHandover dayShift = handoverRow("HO2026092200001", "DAY", day);
        ShiftHandover nightShift = handoverRow("HO2026092200002", "NIGHT", day);
        // mock 按谓词回放：DB 侧仅返回同日两行（跨日行被滤除），班次升序
        when(handoverMapper.selectList(any())).thenReturn(List.of(dayShift, nightShift));

        List<ShiftHandoverVO> result = service.listByWard(WARD, day);

        assertThat(result).extracting(ShiftHandoverVO::shiftCode).containsExactly("DAY", "NIGHT");
        verify(handoverMapper).selectList(handoverQueryCaptor.capture());
        LambdaQueryWrapper<ShiftHandover> wrapper = rendered(handoverQueryCaptor.getValue());
        // 谓词根因锚：病区 + 交接日期（跨日行不返回的依据）+ 班次升序
        assertThat(wrapper.getParamNameValuePairs().values()).contains(WARD, day);
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY").contains("shift_code");
    }

    // ===================== 补充覆盖锚（JaCoCo service.impl LINE=1.00 名单） =====================

    @Test
    @DisplayName("单号唯一冲突兜底：handover_no 唯一键冲突转 NS-1016 幂等拒绝（Task 4/5/6/7 同口径）")
    void generateRejectsDuplicateHandoverNo() {
        stubGenerateBaseline(List.of(wardRow("01", "NORMAL", "", VISIT_A)));
        when(handoverMapper.insert(any(ShiftHandover.class)))
                .thenThrow(new DuplicateKeyException("uk_shift_handover_no"));

        assertThatThrownBy(() -> service.generate(new HandoverGenerateRequest(WARD, SHIFT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("完成回读缺失：CAS 命中后行被并发逻辑删（selectOne 空）拒 NS-1016，不发事件")
    void completeFailsWhenRowVanishesAfterCas() {
        when(handoverMapper.casComplete(HANDOVER_NO, INCOMING, "", "", "", "", "nurse-01"))
                .thenReturn(1);
        when(handoverMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() ->
                        service.complete(HANDOVER_NO, new HandoverCompleteRequest(INCOMING, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                });
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("摘要快照序列化失败：上抛 IllegalStateException，交接班事务整体回滚（fail-fast 不吞）")
    void generateRejectsWhenSummarySerializeFails() {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        try {
            when(broken.writeValueAsString(any())).thenAnswer(inv -> {
                // Answer 可抛 checked 异常：注入序列化故障（快照缺失即交接班不可追溯，拒绝落库）
                throw new JsonProcessingException("序列化故障注入") {};
            });
        } catch (JsonProcessingException e) {
            // mock 替身运行期不会真抛（默认返回 null），此处仅为受检异常的编译期收口
            throw new IllegalStateException(e);
        }
        ShiftHandoverServiceImpl brokenService = new ShiftHandoverServiceImpl(
                handoverMapper, wardPatientMapper, wardMetaService, taskService, seqGate, events, broken);
        ReflectionTestUtils.setField(brokenService, "baseMapper", handoverMapper);
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfig());
        when(wardPatientMapper.selectList(any())).thenReturn(List.of(wardRow("01", "NORMAL", "", VISIT_A)));
        when(taskService.inFlightByVisit(VISIT_A)).thenReturn(List.of());
        when(seqGate.nextNo("HO")).thenReturn(HANDOVER_NO);

        assertThatThrownBy(() -> brokenService.generate(new HandoverGenerateRequest(WARD, SHIFT)))
                .isInstanceOf(IllegalStateException.class);
        verify(handoverMapper, never()).insert(any(ShiftHandover.class));
    }

    @Test
    @DisplayName("库内摘要快照损坏：完成回读解析失败上抛 IllegalStateException（服务端数据异常显式暴露不吞）")
    void completeRejectsCorruptedPatientSummary() {
        when(handoverMapper.casComplete(HANDOVER_NO, INCOMING, "", "", "", "", "nurse-01"))
                .thenReturn(1);
        ShiftHandover corrupted = completedRow();
        corrupted.setPatientSummary("{\"total\": 4"); // 截断 JSON：库内文本损坏场景
        when(handoverMapper.selectOne(any())).thenReturn(corrupted);

        assertThatThrownBy(() ->
                        service.complete(HANDOVER_NO, new HandoverCompleteRequest(INCOMING, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("交接班 JSONB 列解析失败");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 生成链通用桩：配置行通过 + 在区视图回放 + 在途任务全空 + 发号成功（落库桩由用例按需追加）。 */
    private void stubGenerateBaseline(List<NursingWardPatient> wardRows) {
        when(wardMetaService.wardConfig(WARD)).thenReturn(wardConfig());
        when(wardPatientMapper.selectList(any())).thenReturn(wardRows);
        when(taskService.inFlightByVisit(anyString())).thenReturn(List.of());
        when(seqGate.nextNo("HO")).thenReturn(HANDOVER_NO);
    }

    /** 落库成功桩：insert 回填固定 id（生成链正常路径收尾）。 */
    private void stubInsertOk() {
        when(handoverMapper.insert(any(ShiftHandover.class))).thenAnswer(insertWithId(ROW_ID));
    }

    /** 病区配置出参替身（交接班仅消费配置存在性，结构化内容为空集不影响汇总）。 */
    private WardConfigVO wardConfig() {
        return new WardConfigVO(WARD, Map.of(), false, List.of());
    }

    /** 在区视图行替身（床位/护理级别/病情标记三要素为汇总口径的全部输入）。 */
    private NursingWardPatient wardRow(String bedNo, String nursingLevel, String conditionTags, String visitId) {
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(WARD);
        row.setBedNo(bedNo);
        row.setPatientId(7L);
        row.setVisitId(visitId);
        row.setNursingLevel(nursingLevel);
        row.setConditionTags(conditionTags);
        row.setStatus(WardPatientStatus.IN_WARD.getCode());
        return row;
    }

    /** 标准四人在区替身：2 NORMAL（2 新入）/ 1 CRITICAL（手术）/ 1 SPECIAL（用例 1/4 冻结集）。 */
    private List<NursingWardPatient> fourPatients() {
        return List.of(
                wardRow("01", "NORMAL", "NEW", VISIT_A),
                wardRow("02", "NORMAL", "NEW", VISIT_B),
                wardRow("03", "CRITICAL", "SURGERY", VISIT_C),
                wardRow("04", "SPECIAL", "", VISIT_D));
    }

    /** 在途任务出参替身（待续事项四字段 + visitId 的数据源）。 */
    private NursingTaskVO taskVO(String taskNo, String taskType, OffsetDateTime planTime, boolean overdueFlag) {
        return new NursingTaskVO(
                null,
                taskNo,
                7L,
                VISIT_A,
                WARD,
                "01",
                taskType,
                "MANUAL",
                null,
                planTime,
                "nurse-01",
                "NORMAL",
                overdueFlag,
                0,
                TaskStatus.PENDING.getCode(),
                null,
                null);
    }

    /** 交接班行替身（清单用例载体，JSONB 快照列齐备——出参结构化解析的数据源）。 */
    private ShiftHandover handoverRow(String handoverNo, String shiftCode, LocalDate handoverDate) {
        ShiftHandover row = completedRow();
        row.setHandoverNo(handoverNo);
        row.setShiftCode(shiftCode);
        row.setHandoverDate(handoverDate);
        return row;
    }

    /** 完成后行替身（读后写替身：CAS 命中后的回读态，双签落定 + SBAR 初稿保留）。 */
    private ShiftHandover completedRow() {
        ShiftHandover row = new ShiftHandover();
        row.setId(ROW_ID);
        row.setHandoverNo(HANDOVER_NO);
        row.setWardId(WARD);
        row.setShiftCode(SHIFT);
        row.setHandoverDate(LocalDate.now());
        row.setStatus("COMPLETED");
        row.setOutgoingNurseId("nurse-01");
        row.setIncomingNurseId(INCOMING);
        row.setOutgoingSignedAt(OffsetDateTime.now().minusHours(8));
        row.setIncomingSignedAt(OffsetDateTime.now());
        row.setPatientSummary(
                "{\"total\":1,\"specialCount\":0,\"criticalCount\":0,\"newAdmissionCount\":0,\"surgeryCount\":0,"
                        + "\"todayDischargeCount\":0,\"transferOutCount\":0}");
        row.setSbarSituation("本班W01病区在区 1 人，其中特级护理 0 人、病重护理 0 人；危重患者床位：无。");
        row.setSbarBackground("本班病区动态：新入 0 人、手术 0 人、今日出院 0 人、转出 0 人。");
        row.setSbarAssessment("本班患者整体情况请结合特级/病重患者（床位：无）逐床核实病情变化、风险标识与在途治疗任务后确认。");
        row.setSbarRecommendation("请接班护士逐项确认待续事项清单，优先跟进逾期任务，并对特级/病重患者执行床旁交接与双签确认。");
        row.setPendingItems("[]");
        row.setPendingInfusions("[]");
        row.setUnclosedAlarms("[]");
        return row;
    }

    /** insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertWithId(long id) {
        return inv -> {
            inv.getArgument(0, ShiftHandover.class).setId(id);
            return 1;
        };
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private <T> LambdaQueryWrapper<T> rendered(Wrapper<T> captured) {
        LambdaQueryWrapper<T> wrapper = (LambdaQueryWrapper<T>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String recordSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    ShiftHandoverMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}

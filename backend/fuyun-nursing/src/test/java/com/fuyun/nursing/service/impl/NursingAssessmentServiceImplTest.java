package com.fuyun.nursing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
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
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.AssessmentCompletedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.NursingAssessmentCreateRequest;
import com.fuyun.nursing.entity.NursingAssessment;
import com.fuyun.nursing.enums.RiskLevel;
import com.fuyun.nursing.enums.ScaleType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingAssessmentMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingAssessmentVO;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.ScaleDefinitionVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 护理评估单域服务单测（Task 8 十七用例冻结集）：五量表定义暴露、五量表判级阈值边界
 * （BRADEN/MORSE/NRS/BARTHEL/MEWS 逐量表）、条目完整性与取值范围拒收（NS-1010）、量表类型
 * 不支持拒收（NS-1009，CUSTOM 引擎归 P2）、高危联动防范任务（PREVENTION/ASSESSMENT/HIGH/
 * sourceRef=assessNo）、高危风险标识回写（BRADEN→PRESSURE / MORSE→FALL）、复评计划按风险
 * 等级盖章（24h/72h/168h）、业务时间双向强校验（NS-1016）、评估完成事件载荷逐字断言与
 * 患者评估清单过滤。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 */
@ExtendWith(MockitoExtension.class)
class NursingAssessmentServiceImplTest {

    /** I 型 14 位合法 visit_id（评估单所属就诊） */
    private static final String VISIT = "I2026092200001";

    /** 病区编码（在区行归一数据源） */
    private static final String WARD = "W01";

    /** 评估单号（发号器桩固定返回值） */
    private static final String ASSESS_NO = "AS2026092200001";

    /** 高危联动生成的防范任务号（任务服务桩固定返回值） */
    private static final String TASK_NO = "TK2026092200001";

    /** 首条插入行固定 id（insert 桩回填值） */
    private static final long ROW_ID = 801L;

    /** 入区时间（在区行 admitted_at，业务时间下界校验基准；早于全部用例评估时刻） */
    private static final OffsetDateTime ADMISSION = OffsetDateTime.of(2026, 9, 20, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private NursingAssessmentMapper assessmentMapper;

    @Mock
    private NursingSeqGate seqGate;

    @Mock
    private IWardMetaService wardMetaService;

    @Mock
    private INursingTaskService taskService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<NursingAssessment> rowCaptor;

    @Captor
    private ArgumentCaptor<NursingDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<com.fuyun.nursing.dto.NursingTaskCreateRequest> taskReqCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<NursingAssessment>> queryCaptor;

    private NursingAssessmentServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（评估单清单读面）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NursingAssessment.class);
    }

    @BeforeEach
    void setUp() {
        service = new NursingAssessmentServiceImpl(
                assessmentMapper, seqGate, wardMetaService, taskService, events, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", assessmentMapper);
        OperatorContextHolder.set("nurse-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("量表定义暴露：scales() 返回五条且 scaleType 集合逐字等于冻结词表，条目数 6/6/1/10/5")
    void scalesExposeFiveFrozenDefinitions() {
        List<ScaleDefinitionVO> scales = service.scales();

        assertThat(scales).hasSize(5);
        assertThat(scales)
                .extracting(ScaleDefinitionVO::scaleType)
                .containsExactlyInAnyOrder("BRADEN", "MORSE", "NRS", "BARTHEL", "MEWS");
        assertThat(itemCount(scales, "BRADEN")).isEqualTo(6);
        assertThat(itemCount(scales, "MORSE")).isEqualTo(6);
        assertThat(itemCount(scales, "NRS")).isEqualTo(1);
        assertThat(itemCount(scales, "BARTHEL")).isEqualTo(10);
        assertThat(itemCount(scales, "MEWS")).isEqualTo(5);
    }

    @Test
    @DisplayName("BRADEN 判级：全条目 3 分总分 18 → MEDIUM（轻度风险分界上沿）")
    void createComputesBradenTotalAndLevel() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));

        NursingAssessmentVO vo = service.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3)));

        assertThat(vo.totalScore()).isEqualTo(18);
        assertThat(vo.riskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
        verify(assessmentMapper, never()).updateById(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("BRADEN 极高危档：总分 9 → HIGH（≤12 判高危，含 ≤9 极高危档）")
    void createBradenAtNineIsHigh() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        NursingAssessmentVO vo = service.create(request("BRADEN", braden(2, 1, 1, 1, 1, 3)));

        assertThat(vo.totalScore()).isEqualTo(9);
        assertThat(vo.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
    }

    @Test
    @DisplayName("MORSE 边界：总分 45 → HIGH；44 → MEDIUM（边界两侧逐点验证）")
    void createComputesMorseBoundary() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        NursingAssessmentVO high = service.create(request("MORSE", morse(25, 0, 0, 0, 20, 0)));
        NursingAssessmentVO medium = service.create(request("MORSE", morse(25, 15, 0, 0, 4, 0)));

        assertThat(high.totalScore()).isEqualTo(45);
        assertThat(high.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
        assertThat(medium.totalScore()).isEqualTo(44);
        assertThat(medium.riskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
    }

    @Test
    @DisplayName("NRS 边界：7 → HIGH；6 → MEDIUM")
    void createComputesNrsBoundary() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        NursingAssessmentVO high = service.create(request("NRS", Map.of("PAIN_SCORE", 7)));
        NursingAssessmentVO medium = service.create(request("NRS", Map.of("PAIN_SCORE", 6)));

        assertThat(high.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
        assertThat(medium.riskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
    }

    @Test
    @DisplayName("BARTHEL 边界：41 → MEDIUM；40 → HIGH（自理能力越低风险越高）")
    void createComputesBarthelBoundary() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        NursingAssessmentVO medium = service.create(request("BARTHEL", barthel(10, 5, 5, 5, 5, 5, 5, 0, 0, 1)));
        NursingAssessmentVO high = service.create(request("BARTHEL", barthel(10, 5, 5, 5, 5, 5, 5, 0, 0, 0)));

        assertThat(medium.totalScore()).isEqualTo(41);
        assertThat(medium.riskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
        assertThat(high.totalScore()).isEqualTo(40);
        assertThat(high.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
    }

    @Test
    @DisplayName("MEWS 边界：5 → HIGH；4 → MEDIUM")
    void createComputesMewsBoundary() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        NursingAssessmentVO high = service.create(request("MEWS", mews(1, 1, 1, 1, 1)));
        NursingAssessmentVO medium = service.create(request("MEWS", mews(1, 1, 1, 0, 1)));

        assertThat(high.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
        assertThat(medium.riskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
    }

    @Test
    @DisplayName("条目缺失拒收：BRADEN 缺营养条目 → NS-1010（条目缺失不可判级），零落库零联动")
    void createRejectsMissingItemAnswer() {
        Map<String, Integer> missing = braden(3, 3, 3, 3, 3, 3);
        missing.remove("NUTRITION");

        assertThatThrownBy(() -> service.create(request("BRADEN", missing)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.ASSESSMENT_INCOMPLETE);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1010");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(seqGate, events, taskService);
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("取值越界拒收：BRADEN 条目答案 5（超 1–4 冻结范围）→ NS-1010")
    void createRejectsOutOfRangeAnswer() {
        Map<String, Integer> outOfRange = braden(5, 1, 1, 1, 1, 1);

        assertThatThrownBy(() -> service.create(request("BRADEN", outOfRange)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.ASSESSMENT_INCOMPLETE);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1010");
                });
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("量表类型不支持：scaleType=CUSTOM → NS-1009（CUSTOM 引擎归 P2）")
    void createRejectsUnsupportedScaleType() {
        assertThatThrownBy(() -> service.create(request("CUSTOM", Map.of("X", 1))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.SCALE_TYPE_UNSUPPORTED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1009");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(seqGate, events, taskService, wardMetaService);
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName(
            "高危联动防范任务：MORSE 50 分 → taskService.create 调 1 次，"
                    + "taskType=PREVENTION、source=ASSESSMENT、priority=HIGH、sourceRef=assessNo、planTime=now，并回填 triggered_task_ref")
    void createHighRiskGeneratesPreventionTask() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());
        OffsetDateTime before = OffsetDateTime.now();

        service.create(request("MORSE", morse(25, 15, 0, 0, 10, 0)));

        verify(taskService, times(1)).create(taskReqCaptor.capture());
        com.fuyun.nursing.dto.NursingTaskCreateRequest req = taskReqCaptor.getValue();
        assertThat(req.taskType()).isEqualTo("PREVENTION");
        assertThat(req.source()).isEqualTo("ASSESSMENT");
        assertThat(req.priority()).isEqualTo("HIGH");
        assertThat(req.sourceRef()).isEqualTo(ASSESS_NO);
        assertThat(req.patientId()).isEqualTo(7L);
        assertThat(req.visitId()).isEqualTo(VISIT);
        assertThat(req.wardId()).isEqualTo(WARD);
        assertThat(req.planTime()).isCloseTo(OffsetDateTime.now(), within(2, ChronoUnit.SECONDS));
        assertThat(req.planTime()).isAfterOrEqualTo(before);
        // 防范任务号回填评估单 triggered_task_ref（同事务回写，联动链可追溯）
        verify(assessmentMapper).updateById(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getTriggeredTaskRef()).isEqualTo(TASK_NO);
    }

    @Test
    @DisplayName("低风险零联动：NRS 2 分 → 防范任务与风险标识回写均不触发")
    void createLowRiskGeneratesNoTask() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));

        service.create(request("NRS", Map.of("PAIN_SCORE", 2)));

        verifyNoInteractions(taskService);
        verify(wardMetaService, never()).appendRiskFlag(any(), any());
        verify(assessmentMapper, never()).updateById(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("高危风险标识回写：BRADEN 8 分 → appendRiskFlag(PRESSURE)；MORSE 50 分 → appendRiskFlag(FALL)；"
            + "判重由 appendRiskFlag 内部承载，服务侧每评估各恰调 1 次")
    void createHighRiskUpdatesWardPatientRiskFlags() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        service.create(request("BRADEN", braden(2, 1, 1, 1, 1, 2)));
        service.create(request("MORSE", morse(25, 15, 0, 0, 10, 0)));

        verify(wardMetaService, times(1)).appendRiskFlag(VISIT, "PRESSURE");
        verify(wardMetaService, times(1)).appendRiskFlag(VISIT, "FALL");
    }

    @Test
    @DisplayName("复评计划盖章：HIGH +24h / MEDIUM +72h / LOW +168h（以 assessedAt 为基准）")
    void createStampsNextAssessPlanByRiskLevel() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO, ASSESS_NO + "X", ASSESS_NO + "Y");
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());
        OffsetDateTime assessed = OffsetDateTime.now().minusMinutes(30);

        service.create(request("BRADEN", braden(2, 1, 1, 1, 1, 3), assessed));
        service.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3), assessed));
        service.create(request("NRS", Map.of("PAIN_SCORE", 2), assessed));

        verify(assessmentMapper, times(3)).insert(rowCaptor.capture());
        List<NursingAssessment> rows = rowCaptor.getAllValues();
        assertThat(rows.get(0).getRiskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
        assertThat(rows.get(0).getNextAssessPlan()).isEqualTo(assessed.plusHours(24));
        assertThat(rows.get(1).getRiskLevel()).isEqualTo(RiskLevel.MEDIUM.getCode());
        assertThat(rows.get(1).getNextAssessPlan()).isEqualTo(assessed.plusHours(72));
        assertThat(rows.get(2).getRiskLevel()).isEqualTo(RiskLevel.LOW.getCode());
        assertThat(rows.get(2).getNextAssessPlan()).isEqualTo(assessed.plusHours(168));
    }

    @Test
    @DisplayName("业务时间双向强校验：assessedAt 晚于服务器当前时间 → NS-1016；早于入区时间 → NS-1016")
    void createRejectsAssessedAtInFutureOrBeforeAdmission() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        Map<String, Integer> answers = braden(3, 3, 3, 3, 3, 3);

        assertThatThrownBy(() -> service.create(
                        request("BRADEN", answers, OffsetDateTime.now().plusHours(1))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThatThrownBy(() -> service.create(request("BRADEN", answers, ADMISSION.minusHours(1))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                });
        verifyNoInteractions(seqGate, events, taskService);
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("评估完成事件：nursing.assessment.completed 载荷 assessNo/scaleType/totalScore/riskLevel 逐字断言")
    void createPublishesAssessmentCompletedEvent() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class))).thenAnswer(insertWithId(ROW_ID));
        when(taskService.create(any())).thenReturn(taskVO());

        service.create(request("MORSE", morse(25, 15, 0, 0, 10, 0)));

        verify(events, times(1)).publishEvent(eventCaptor.capture());
        NursingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(NursingMessagingConstants.EVENT_ASSESSMENT_COMPLETED);
        AssessmentCompletedPayload payload = (AssessmentCompletedPayload) event.payload();
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.assessNo()).isEqualTo(ASSESS_NO);
        assertThat(payload.scaleType()).isEqualTo("MORSE");
        assertThat(payload.totalScore()).isEqualTo(50);
        assertThat(payload.riskLevel()).isEqualTo(RiskLevel.HIGH.getCode());
    }

    @Test
    @DisplayName("患者评估清单：scaleType 过滤跨量表行不返回，按 assessedAt 降序（DB 侧排序钉死）")
    void listByVisitFiltersByScaleType() {
        NursingAssessment later = assessmentRow("AS2026092200002", "BRADEN", 18, RiskLevel.MEDIUM);
        NursingAssessment earlier = assessmentRow("AS2026092200001", "BRADEN", 9, RiskLevel.HIGH);
        when(assessmentMapper.selectList(any())).thenReturn(List.of(later, earlier));

        List<NursingAssessmentVO> result = service.listByVisit(VISIT, ScaleType.BRADEN);

        // 降序：mock 按评估时点降序回放，出参保持同序（DB 侧 ORDER BY assessed_at DESC 钉死）
        assertThat(result)
                .extracting(NursingAssessmentVO::assessNo)
                .containsExactly("AS2026092200002", "AS2026092200001");
        verify(assessmentMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<NursingAssessment> wrapper = rendered(queryCaptor.getValue());
        // 谓词根因锚：visit_id + scale_type（跨量表行由 DB 谓词滤除）
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, "BRADEN");
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY").contains("assessed_at");
    }

    // ===================== 补充覆盖锚（JaCoCo nursing.service.impl LINE=1.00 名单） =====================

    @Test
    @DisplayName("患者不在区拒收：在区行不存在（detail NS-1001）→ 评估域转 NS-1004，零落库零联动（Task 4/5/6 同口径）")
    void createRejectsWhenPatientNotInWard() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(
                        NursingErrorCode.WARD_PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "病区在区患者不存在：" + VISIT));

        assertThatThrownBy(() -> service.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PATIENT_BLOCKED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1004");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(seqGate, events, taskService);
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("在区校验透传：病区服务其他业务异常原样上抛（仅在区缺失才翻译为 NS-1004，Task 4/5/6 同口径）")
    void createPropagatesUnrelatedWardBizException() {
        when(wardMetaService.detail(VISIT))
                .thenThrow(new BizException(NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "其他校验失败"));

        assertThatThrownBy(() -> service.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NursingErrorCode.PARAM_FORMAT_INVALID));
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
        verifyNoInteractions(seqGate, events, taskService);
    }

    @Test
    @DisplayName("评估单号唯一冲突兜底：assess_no 唯一键冲突转 NS-1016 幂等拒绝，不发事件（Task 4/5/6/7 同口径）")
    void createRejectsDuplicateAssessNo() {
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());
        when(seqGate.nextNo("AS")).thenReturn(ASSESS_NO);
        when(assessmentMapper.insert(any(NursingAssessment.class)))
                .thenThrow(new DuplicateKeyException("uk_nursing_assessment_no"));

        assertThatThrownBy(() -> service.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.CONFLICT);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1016");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(events, taskService);
    }

    @Test
    @DisplayName("应答快照序列化失败：上抛 IllegalStateException，评估事务整体回滚（fail-fast 不吞）")
    void createRejectsWhenAnswerSnapshotSerializeFails() {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        try {
            when(broken.writeValueAsString(any())).thenAnswer(inv -> {
                // Answer 可抛 checked 异常：注入序列化故障（快照缺失即评估不可追溯，拒绝落库）
                throw new JsonProcessingException("序列化故障注入") {};
            });
        } catch (JsonProcessingException e) {
            // mock 替身运行期不会真抛（默认返回 null），此处仅为受检异常的编译期收口
            throw new IllegalStateException(e);
        }
        NursingAssessmentServiceImpl brokenService = new NursingAssessmentServiceImpl(
                assessmentMapper, seqGate, wardMetaService, taskService, events, broken);
        ReflectionTestUtils.setField(brokenService, "baseMapper", assessmentMapper);
        when(wardMetaService.detail(VISIT)).thenReturn(detailVO());

        assertThatThrownBy(() -> brokenService.create(request("BRADEN", braden(3, 3, 3, 3, 3, 3))))
                .isInstanceOf(IllegalStateException.class);
        verify(assessmentMapper, never()).insert(any(NursingAssessment.class));
    }

    @Test
    @DisplayName("应答快照损坏：清单回读解析失败上抛 IllegalStateException（服务端数据异常显式暴露不吞）")
    void listByVisitRejectsCorruptedAnswerSnapshot() {
        NursingAssessment corrupted = assessmentRow("AS2026092200001", "BRADEN", 18, RiskLevel.MEDIUM);
        corrupted.setAnswers("{\"PERCEPTION\": 3"); // 截断 JSON：库内文本损坏场景
        when(assessmentMapper.selectList(any())).thenReturn(List.of(corrupted));

        assertThatThrownBy(() -> service.listByVisit(VISIT, ScaleType.BRADEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("评估应答快照解析失败");
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 在区详情卡替身（patientId/wardId 归一与 admitted_at 业务时间下界的数据源）。 */
    private WardPatientDetailVO detailVO() {
        return new WardPatientDetailVO(
                WARD, "01", 7L, VISIT, "张三", "M", 56, "NORMAL", "", false, "", ADMISSION, List.of(), List.of(),
                List.of());
    }

    /** 评估创建请求替身（assessedAt 缺省取服务器当前时间前一分钟，落在合法业务时间窗内）。 */
    private NursingAssessmentCreateRequest request(String scaleType, Map<String, Integer> answers) {
        return request(scaleType, answers, OffsetDateTime.now().minusMinutes(1));
    }

    /** 评估创建请求替身（显式评估时刻，业务时间校验用例定向构造）。 */
    private NursingAssessmentCreateRequest request(
            String scaleType, Map<String, Integer> answers, OffsetDateTime assessedAt) {
        return new NursingAssessmentCreateRequest(VISIT, scaleType, answers, assessedAt);
    }

    /** BRADEN 应答替身（感知/潮湿/活动/移动/营养/摩擦剪切六条目）。 */
    private Map<String, Integer> braden(
            int perception, int moisture, int activity, int mobility, int nutrition, int friction) {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("PERCEPTION", perception);
        answers.put("MOISTURE", moisture);
        answers.put("ACTIVITY", activity);
        answers.put("MOBILITY", mobility);
        answers.put("NUTRITION", nutrition);
        answers.put("FRICTION", friction);
        return answers;
    }

    /** MORSE 应答替身（跌倒史/其他诊断/行走辅助/静脉输液/步态/认知六条目）。 */
    private Map<String, Integer> morse(int fallHistory, int diagnosis, int aid, int iv, int gait, int mental) {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("FALL_HISTORY", fallHistory);
        answers.put("SECOND_DIAGNOSIS", diagnosis);
        answers.put("AMBULATORY_AID", aid);
        answers.put("IV_THERAPY", iv);
        answers.put("GAIT", gait);
        answers.put("MENTAL_STATUS", mental);
        return answers;
    }

    /** BARTHEL 应答替身（进食/洗澡/修饰/穿衣/大便/小便/如厕/转移/行走/楼梯十条目）。 */
    private Map<String, Integer> barthel(
            int feeding,
            int bathing,
            int grooming,
            int dressing,
            int bowels,
            int bladder,
            int toileting,
            int transfer,
            int walking,
            int stairs) {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("FEEDING", feeding);
        answers.put("BATHING", bathing);
        answers.put("GROOMING", grooming);
        answers.put("DRESSING", dressing);
        answers.put("BOWELS", bowels);
        answers.put("BLADDER", bladder);
        answers.put("TOILETING", toileting);
        answers.put("TRANSFER", transfer);
        answers.put("WALKING", walking);
        answers.put("STAIRS", stairs);
        return answers;
    }

    /** MEWS 应答替身（收缩压/心率/呼吸/体温/意识五参数）。 */
    private Map<String, Integer> mews(int sbp, int hr, int rr, int temp, int consciousness) {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("SBP", sbp);
        answers.put("HR", hr);
        answers.put("RR", rr);
        answers.put("TEMP", temp);
        answers.put("CONSCIOUSNESS", consciousness);
        return answers;
    }

    /** 评估单行替身（answers 落库为 JSONB 文本，与 insert 写面同形态）。 */
    private NursingAssessment assessmentRow(String assessNo, String scaleType, int totalScore, RiskLevel level) {
        NursingAssessment row = new NursingAssessment();
        row.setId(ROW_ID);
        row.setAssessNo(assessNo);
        row.setPatientId(7L);
        row.setVisitId(VISIT);
        row.setWardId(WARD);
        row.setScaleType(scaleType);
        row.setAnswers("{\"PAIN_SCORE\":2}");
        row.setTotalScore(totalScore);
        row.setRiskLevel(level.getCode());
        row.setAssessedAt(OffsetDateTime.now().minusMinutes(1));
        row.setAssessedBy("nurse-01");
        return row;
    }

    /** 高危联动防范任务出参替身（taskNo 为回填 triggered_task_ref 的来源值）。 */
    private NursingTaskVO taskVO() {
        return new NursingTaskVO(
                901L,
                TASK_NO,
                7L,
                VISIT,
                WARD,
                null,
                "PREVENTION",
                "ASSESSMENT",
                ASSESS_NO,
                OffsetDateTime.now(),
                null,
                "HIGH",
                false,
                0,
                "PENDING",
                null,
                null);
    }

    /** insert 桩：回填固定 id 并返回影响行数 1。 */
    private org.mockito.stubbing.Answer<Integer> insertWithId(long id) {
        return inv -> {
            inv.getArgument(0, NursingAssessment.class).setId(id);
            return 1;
        };
    }

    /** 按量表 code 取定义出参的条目数（定义暴露用例断言辅助）。 */
    private int itemCount(List<ScaleDefinitionVO> scales, String scaleType) {
        return scales.stream()
                .filter(s -> s.scaleType().equals(scaleType))
                .findFirst()
                .map(s -> s.itemCodes().size())
                .orElse(-1);
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<NursingAssessment> rendered(Wrapper<NursingAssessment> captured) {
        LambdaQueryWrapper<NursingAssessment> wrapper = (LambdaQueryWrapper<NursingAssessment>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }
}

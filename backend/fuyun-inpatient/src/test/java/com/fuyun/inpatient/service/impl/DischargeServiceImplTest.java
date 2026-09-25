package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingAccountQueryPort;
import com.fuyun.billing.api.DischargePrecheckView;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderAuditedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargeRequestedPayload;
import com.fuyun.inpatient.api.payload.VisitDischargedPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.DischargeConfirmRequest;
import com.fuyun.inpatient.dto.DischargeRequestCreate;
import com.fuyun.inpatient.entity.DischargeRequest;
import com.fuyun.inpatient.entity.FollowUpPlan;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.OrderAudit;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.enums.DischargeRequestStatus;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.OrderType;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.DischargeRequestMapper;
import com.fuyun.inpatient.mapper.FollowUpPlanMapper;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderAuditMapper;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import com.fuyun.inpatient.properties.InpatientProperties;
import com.fuyun.inpatient.service.BedService;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.ClearanceVO;
import com.fuyun.inpatient.vo.DischargeRequestVO;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

/**
 * 出院管理服务单测（Task 9 冻结七用例 + LINE=1.00 覆盖率补面）：①出院申请全编排（清理三
 * 动作+预审 BLOCKED[欠费]+discharge-requested 事件）②预审通过 READY③挂账审批放行
 * （arrears.approved 消费→BLOCKED→READY）④结算完成标记（settlement.completed 消费）
 * ⑤离院确认双条件（READY+已结算→DISCHARGED+床位消毒+带药放行[audited.discharge-med
 * 子键]+discharged 事件+随访生成）⑥单条件拒绝（READY 未结算/BLOCKED 均 IP-1017）
 * ⑦取消回在院（医嘱不复活断言——停终态保持，零医嘱迁移调用）；补面：守卫全集（IP-1007/
 * 1008/1017/1018/1022/1023）、并发 CAS 零行、快照序列化/解析异常、押金空值欠费计算、
 * 消费幂等跳过三分支、随访缺省参数。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll
 * 手工注册表信息；真实 ObjectMapper 承载 JSONB 快照往返（序列化失败分支以 mock 触发）。
 */
@ExtendWith(MockitoExtension.class)
class DischargeServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（discharge_request.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（REST 面操作者上下文口径） */
    private static final long OPERATOR = 2001L;

    /** 出院申请单号 */
    private static final String REQUEST_NO = "DC2026092500001";

    /** 在院床位（离院确认终末消毒流转对象） */
    private static final long BED_ID = 5555L;

    /** 出院申请时点（visit 回读行携带——库端 now() 的单测替身） */
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.of(2026, 9, 25, 9, 0, 0, 0, ZoneOffset.UTC);

    /** 出院完成时点（visit 回读行携带——随访日期与事件载荷源） */
    private static final OffsetDateTime DISCHARGED_AT = OffsetDateTime.of(2026, 9, 25, 14, 30, 0, 0, ZoneOffset.UTC);

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private OrderExecutePlanMapper planMapper;

    @Mock
    private OrderAuditMapper auditMapper;

    @Mock
    private DischargeRequestMapper requestMapper;

    @Mock
    private FollowUpPlanMapper followUpMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private MedicalOrderService medicalOrderService;

    @Mock
    private BedService bedService;

    @Mock
    private OrderStateMachineService stateMachine;

    @Mock
    private BillingAccountQueryPort billingAccountQueryPort;

    /** 住院域参数（默认值实例——随访缺省时距 14 日/欠费阈值 0/准备窗口 60 分钟，与应用缺省同源） */
    private final InpatientProperties properties = new InpatientProperties(0L, 14, 60);

    @Mock
    private ApplicationEventPublisher events;

    private ObjectMapper objectMapper;

    private DischargeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（本域查询面五实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecutePlan.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DischargeRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FollowUpPlan.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new DischargeServiceImpl(
                visitMapper,
                orderMapper,
                planMapper,
                auditMapper,
                requestMapper,
                followUpMapper,
                seqGate,
                medicalOrderService,
                bedService,
                stateMachine,
                billingAccountQueryPort,
                properties,
                events,
                objectMapper);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①出院申请全编排：清理三动作+预审 BLOCKED[欠费快照]+discharge-requested 事件（id 50 载荷逐字）")
    void createRequestRunsClearanceAndBlockedPrecheck() {
        // 医嘱三分野：LONG-EXECUTING（停嘱面）/STAT-TRANSFERRED（追踪）/LONG-CREATED（追踪——无合法停嘱边）
        MedicalOrder stoppable =
                orderRow(9001L, "MO2026092500001", OrderClass.LONG.getCode(), OrderStatus.EXECUTING, OrderType.DRUG);
        MedicalOrder statOngoing =
                orderRow(9002L, "MO2026092500002", OrderClass.STAT.getCode(), OrderStatus.TRANSFERRED, OrderType.DRUG);
        MedicalOrder pendingAudit =
                orderRow(9003L, "MO2026092500003", OrderClass.LONG.getCode(), OrderStatus.CREATED, OrderType.DRUG);
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED), requestedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of(stoppable, statOngoing, pendingAudit, completedOrder()));
        when(planMapper.selectCount(any())).thenReturn(2L);
        when(billingAccountQueryPort.precheck(VISIT_ID)).thenReturn(new DischargePrecheckView(50000L, 20000L, false));
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(1);
        when(seqGate.nextNo("DC")).thenReturn(REQUEST_NO);
        when(requestMapper.insert(any(DischargeRequest.class))).thenReturn(1);

        DischargeRequestVO vo =
                service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT.plusDays(1), "1"));

        // 动作①：长期可停医嘱批量停嘱（复用停嘱面，reason=出院固定文案）
        verify(medicalOrderService).stopAllForTransfer(VISIT_PK, "出院");
        // 动作③：在途 PENDING 计数 2——就诊全医嘱批量作废（含终态行兜底遗漏）
        verify(planMapper)
                .cancelPendingByOrderIds(eq(List.of(9001L, 9002L, 9003L, 9100L)), eq(String.valueOf(OPERATOR)));
        // 预审 BLOCKED：欠费额=max(0,50000-20000)=30000 分（GC18 快照回显）
        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.BLOCKED.getCode());
        assertThat(vo.arrearsAmount()).isEqualTo(30000L);
        assertThat(vo.requestNo()).isEqualTo(REQUEST_NO);
        // 申请行落库断言：预审态直落 + 清理快照三计数（LONG 停 1/追踪 2[STAT+CREATED]/计划作废 2）
        ArgumentCaptor<DischargeRequest> rowCaptor = ArgumentCaptor.forClass(DischargeRequest.class);
        verify(requestMapper).insert(rowCaptor.capture());
        DischargeRequest saved = rowCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DischargeRequestStatus.BLOCKED.getCode());
        assertThat(saved.getArrearsAmount()).isEqualTo(30000L);
        assertThat(saved.getVisitId()).isEqualTo(VISIT_PK);
        assertThat(saved.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(saved.getRequesterId()).isEqualTo(String.valueOf(OPERATOR));
        assertThat(saved.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(saved.getExpectDischargeAt()).isEqualTo(REQUESTED_AT.plusDays(1));
        assertThat(saved.getDischargeWay()).isEqualTo("1");
        assertThat(saved.getClearanceResult())
                .contains("\"stoppedLongCount\":1")
                .contains("\"cancelledPlanCount\":2")
                .contains("MO2026092500002")
                .contains("MO2026092500003")
                .doesNotContain("MO2026092500001");
        // 事件（id 50 载荷逐字）：visitId/patientId/requestedAt——visit 回读行时点
        ArgumentCaptor<InpatientDomainEvent> eventCaptor = ArgumentCaptor.forClass(InpatientDomainEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_DISCHARGE_REQUESTED);
        VisitDischargeRequestedPayload payload = (VisitDischargeRequestedPayload) event.payload();
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.requestedAt()).isEqualTo(REQUESTED_AT.toInstant());
    }

    @Test
    @DisplayName("用例②预审通过 READY：空在途直过（零停嘱零作废零追踪）+欠费额空")
    void createRequestMarksReadyWhenSettled() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED), requestedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(billingAccountQueryPort.precheck(VISIT_ID)).thenReturn(new DischargePrecheckView(0L, 0L, true));
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(1);
        when(seqGate.nextNo("DC")).thenReturn(REQUEST_NO);
        when(requestMapper.insert(any(DischargeRequest.class))).thenReturn(1);

        DischargeRequestVO vo =
                service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT.plusDays(1), "1"));

        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.READY.getCode());
        assertThat(vo.arrearsAmount()).isNull();
        // 空在途：停嘱面与计划作废零调用（空集直过语义）
        verify(medicalOrderService, never()).stopAllForTransfer(any(), anyString());
        verify(planMapper, never()).cancelPendingByOrderIds(anyList(), anyString());
        verify(events).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("押金空值欠费计算：未结清全额即欠费（depositBalance=null 容错为 0）")
    void createRequestComputesArrearsWhenDepositNull() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED), requestedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(billingAccountQueryPort.precheck(VISIT_ID)).thenReturn(new DischargePrecheckView(50000L, null, false));
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(1);
        when(seqGate.nextNo("DC")).thenReturn(REQUEST_NO);
        when(requestMapper.insert(any(DischargeRequest.class))).thenReturn(1);

        DischargeRequestVO vo =
                service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT.plusDays(1), "2"));

        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.BLOCKED.getCode());
        assertThat(vo.arrearsAmount()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("清理快照序列化失败 fail-closed（IP-1023——不可达防御面）")
    void createRequestFailsWhenSnapshotSerializationFails() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        service = new DischargeServiceImpl(
                visitMapper,
                orderMapper,
                planMapper,
                auditMapper,
                requestMapper,
                followUpMapper,
                seqGate,
                medicalOrderService,
                bedService,
                stateMachine,
                billingAccountQueryPort,
                properties,
                events,
                failing);
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED), requestedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(billingAccountQueryPort.precheck(VISIT_ID)).thenReturn(new DischargePrecheckView(0L, 0L, true));
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(1);
        when(seqGate.nextNo("DC")).thenReturn(REQUEST_NO);

        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("清理结果快照序列化失败");
        // 序列化失败即阻断——申请行不落库、事件不发
        verify(requestMapper, never()).insert(any(DischargeRequest.class));
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("申请守卫面：就诊不存在 IP-1007/非在院 IP-1008/离院方式词表外 IP-1022/操作者缺失 IP-1022")
    void createRequestGuards() {
        // 就诊不存在
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND))
                .hasMessageContaining("住院就诊不存在");

        // 非在院态（已出院终态禁再申请）
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.DISCHARGED));
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED))
                .hasMessageContaining("不允许申请出院");

        // 离院方式词表外（病案首页代码 "7" 不在词表）
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "7")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID))
                .hasMessageContaining("dischargeWay");

        // 操作者标识缺失（无登录上下文）
        OperatorContextHolder.clear();
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID))
                .hasMessageContaining("操作者标识缺失");
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @Test
    @DisplayName("申请并发面：visit CAS 零行 IP-1023/在途申请唯一冲突（uk_visit_active）IP-1023")
    void createRequestRejectsConcurrentWindows() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(billingAccountQueryPort.precheck(VISIT_ID)).thenReturn(new DischargePrecheckView(0L, 0L, true));

        // visit CAS 零行（并发重复申请/已迁移）
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(0);
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("并发冲突");

        // uk_visit_active 唯一冲突（并发窗口 DB 硬防线触发）
        when(visitMapper.casRequestDischarge(VISIT_ID, String.valueOf(OPERATOR)))
                .thenReturn(1);
        when(seqGate.nextNo("DC")).thenReturn(REQUEST_NO);
        when(requestMapper.insert(any(DischargeRequest.class))).thenThrow(new DuplicateKeyException("uk_visit_active"));
        assertThatThrownBy(() -> service.createRequest(VISIT_ID, new DischargeRequestCreate(REQUESTED_AT, "1")))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("在途出院申请唯一冲突");
    }

    @Test
    @DisplayName("用例⑦取消回在院：visit→ADMITTED+申请→CANCELLED，医嘱不复活（零医嘱迁移/停嘱调用）")
    void cancelRestoresAdmittedWithoutOrderRevival() {
        // 停终态医嘱夹具（取消后状态仍 STOPPED 的语义载体——取消面不触碰任何医嘱行）
        MedicalOrder stopped =
                orderRow(9001L, "MO2026092500001", OrderClass.LONG.getCode(), OrderStatus.STOPPED, OrderType.DRUG);
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.REQUESTED, null, null));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));
        when(visitMapper.casCancelDischarge(VISIT_ID, String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casCancel(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(1);

        DischargeRequestVO vo = service.cancel(REQUEST_NO);

        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.CANCELLED.getCode());
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        // 医嘱不复活断言：取消面零医嘱状态迁移、零停嘱值面调用（STOPPED 终态保持——恢复治疗须重新开立）
        verifyNoInteractions(medicalOrderService, stateMachine);
        verify(orderMapper, never()).updateStopValues(any(), any(), any(), any());
        assertThat(stopped.getStatus()).isEqualTo(OrderStatus.STOPPED.getCode());
        // 取消不发事件（登记面无对应事件契约）
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("取消守卫与并发面：不存在 IP-1018/非 REQUESTED IP-1017/就诊缺失 IP-1007/两 CAS 零行 IP-1023")
    void cancelGuards() {
        // 申请不存在
        when(requestMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(REQUEST_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_REQUEST_NOT_FOUND));

        // 非 REQUESTED 态（READY 须先业务裁决）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        assertThatThrownBy(() -> service.cancel(REQUEST_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_NOT_ALLOWED))
                .hasMessageContaining("仅申请中");

        // 关联就诊缺失（数据不一致防御）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.REQUESTED, null, null));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.cancel(REQUEST_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // visit CAS 零行（并发离院确认——取消与确认互斥窗口）
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));
        when(visitMapper.casCancelDischarge(VISIT_ID, String.valueOf(OPERATOR))).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(REQUEST_NO))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("取消出院就诊状态并发冲突");

        // 申请 CAS 零行（并发迁移兜底）
        when(visitMapper.casCancelDischarge(VISIT_ID, String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casCancel(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(0);
        assertThatThrownBy(() -> service.cancel(REQUEST_NO))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("取消出院申请并发冲突");
    }

    @Test
    @DisplayName("clearance 查询：清理快照+预审+结算标记+审批凭证聚合回显")
    void clearanceReturnsSnapshotAndPrecheck() throws Exception {
        String json = objectMapper.writeValueAsString(new DischargeServiceImpl.ClearanceSnapshot(
                1, List.of(new ClearanceVO.TrackedOrderVO("MO2026092500002", "STAT", "TRANSFERRED")), 2));
        DischargeRequest row = requestRow(DischargeRequestStatus.BLOCKED, null, null);
        row.setClearanceResult(json);
        row.setArrearsAmount(30000L);
        when(requestMapper.selectOne(any())).thenReturn(row);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));

        ClearanceVO vo = service.clearance(REQUEST_NO);

        assertThat(vo.requestNo()).isEqualTo(REQUEST_NO);
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.BLOCKED.getCode());
        assertThat(vo.stoppedLongCount()).isEqualTo(1);
        assertThat(vo.cancelledPlanCount()).isEqualTo(2);
        assertThat(vo.trackedOrders())
                .containsExactly(new ClearanceVO.TrackedOrderVO("MO2026092500002", "STAT", "TRANSFERRED"));
        assertThat(vo.arrearsAmount()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("clearance 守卫面：不存在 IP-1018/就诊缺失 IP-1007/空快照回零/损坏 JSON 定性 IP-1023")
    void clearanceGuards() {
        // 申请不存在
        when(requestMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.clearance(REQUEST_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_REQUEST_NOT_FOUND));

        // 就诊缺失
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.clearance(REQUEST_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 空快照防御（历史行/异常行 clearance_result 为空——计数回零不阻断查询）
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));
        DischargeRequest blank = requestRow(DischargeRequestStatus.READY, null, null);
        blank.setClearanceResult("  ");
        when(requestMapper.selectOne(any())).thenReturn(blank);
        ClearanceVO vo = service.clearance(REQUEST_NO);
        assertThat(vo.stoppedLongCount()).isZero();
        assertThat(vo.cancelledPlanCount()).isZero();
        assertThat(vo.trackedOrders()).isEmpty();

        // 损坏 JSON（数据不一致——fail-closed 定性冲突）
        DischargeRequest corrupted = requestRow(DischargeRequestStatus.READY, null, null);
        corrupted.setClearanceResult("{not-json");
        when(requestMapper.selectOne(any())).thenReturn(corrupted);
        assertThatThrownBy(() -> service.clearance(REQUEST_NO))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("清理结果快照解析失败");
    }

    @Test
    @DisplayName("用例⑤离院确认双条件满足：DISCHARGED+床位消毒+带药放行[audited.discharge-med 子键]+discharged 事件+随访生成")
    void confirmDischargesWithDoubleConditionAndFullEffects() {
        OffsetDateTime settledAt = OffsetDateTime.of(2026, 9, 25, 14, 0, 0, 0, ZoneOffset.UTC);
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, settledAt, null));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectCount(any())).thenReturn(0L);
        InpatientVisit visiting = visitRow(VisitStatus.DISCHARGE_REQUESTED);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visiting, dischargedVisitRow());
        MedicalOrder dischargeMed = orderRow(
                9200L, "MO2026092500100", OrderClass.STAT.getCode(), OrderStatus.CREATED, OrderType.DISCHARGE_MED);
        when(orderMapper.selectList(any())).thenReturn(List.of(dischargeMed));
        when(orderMapper.updateAuditBegin(eq("MO2026092500100"), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.casDischarge(VISIT_ID, "1", String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casComplete(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(1);

        DischargeRequestVO vo = service.confirm(REQUEST_NO, new DischargeConfirmRequest(14, "REVISIT", "两周后心内科复诊"));

        assertThat(vo.status()).isEqualTo(DischargeRequestStatus.COMPLETED.getCode());
        // 带药放行：状态机唯一裁决面迁 AUDITED（留痕 reason=出院带药放行）+ SYSTEM 审计行
        verify(stateMachine).transition(dischargeMed, OrderStatus.AUDITED, "出院带药放行", OPERATOR);
        ArgumentCaptor<OrderAudit> auditCaptor = ArgumentCaptor.forClass(OrderAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getStage()).isEqualTo("SYSTEM");
        assertThat(auditCaptor.getValue().getConclusion()).isEqualTo("PASSED");
        assertThat(auditCaptor.getValue().getReason()).isEqualTo("出院带药放行");
        // 床位 OCCUPIED→DISINFECTING 终末消毒流转（转科转出床同款）
        verify(bedService).transferOut(BED_ID, VISIT_ID);
        // 随访生成：plan_date=出院日+14 日（显式参数），方式/摘要逐参透传
        ArgumentCaptor<FollowUpPlan> planCaptor = ArgumentCaptor.forClass(FollowUpPlan.class);
        verify(followUpMapper).insert(planCaptor.capture());
        FollowUpPlan plan = planCaptor.getValue();
        assertThat(plan.getVisitId()).isEqualTo(VISIT_PK);
        assertThat(plan.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(plan.getPlanDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(plan.getWay()).isEqualTo("REVISIT");
        assertThat(plan.getSummary()).isEqualTo("两周后心内科复诊");
        assertThat(plan.getStatus()).isEqualTo("PENDING");
        // 事件双发：audited.discharge-med 子键（M06 摆药依据）+ discharged（id 51 载荷逐字）
        ArgumentCaptor<InpatientDomainEvent> eventCaptor = ArgumentCaptor.forClass(InpatientDomainEvent.class);
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        InpatientDomainEvent auditedEvent = eventCaptor.getAllValues().get(0);
        assertThat(auditedEvent.eventType())
                .isEqualTo(InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_AUDITED, "discharge-med"));
        OrderAuditedPayload auditedPayload = (OrderAuditedPayload) auditedEvent.payload();
        assertThat(auditedPayload.m04OrderNo()).isEqualTo("MO2026092500100");
        assertThat(auditedPayload.visitId()).isEqualTo(VISIT_ID);
        assertThat(auditedPayload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(auditedPayload.auditType()).isEqualTo("SYSTEM");
        assertThat(auditedPayload.auditOperator()).isEqualTo(String.valueOf(OPERATOR));
        InpatientDomainEvent dischargedEvent = eventCaptor.getAllValues().get(1);
        assertThat(dischargedEvent.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_VISIT_DISCHARGED);
        VisitDischargedPayload payload = (VisitDischargedPayload) dischargedEvent.payload();
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.dischargedAt()).isEqualTo(DISCHARGED_AT.toInstant());
    }

    @Test
    @DisplayName("随访缺省参数：三参全空→域参数缺省 14 日后/电话/「出院随访」（出院必随随访；Task 10 回接——原 Task 9 常量缺省 7 日，行为变更随 2026-09-25 报告留痕）")
    void confirmUsesDefaultFollowUpWhenParamsAbsent() {
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, REQUESTED_AT, null));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(visitMapper.selectById(VISIT_PK))
                .thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED), dischargedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(visitMapper.casDischarge(VISIT_ID, "1", String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casComplete(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(1);

        service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, null, null));

        ArgumentCaptor<FollowUpPlan> planCaptor = ArgumentCaptor.forClass(FollowUpPlan.class);
        verify(followUpMapper).insert(planCaptor.capture());
        // 缺省时距取 InpatientProperties.followUpIntervalDays（默认 14——出院日 2026-09-25 + 14 = 2026-10-09）
        assertThat(planCaptor.getValue().getPlanDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(planCaptor.getValue().getWay()).isEqualTo("PHONE");
        assertThat(planCaptor.getValue().getSummary()).isEqualTo("出院随访");
        // 无带药医嘱（空集直过）——仅 discharged 单事件
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("用例⑥单条件拒绝：BLOCKED 态/READY 未结算均 IP-1017（双条件缺一不可）")
    void confirmRejectsSingleCondition() {
        // BLOCKED 态（欠费未放行——预审面未过）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.BLOCKED, null, null));
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_NOT_ALLOWED))
                .hasMessageContaining("申请状态非 READY");

        // READY 但未结算（结算标记缺失——双条件之一不满足）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_NOT_ALLOWED))
                .hasMessageContaining("出院结算未完成");
        // 拒绝面零副作用：不迁移、不放行、不发事件
        verifyNoInteractions(stateMachine, bedService, events);
    }

    @Test
    @DisplayName("GC19 前置③④：非终态长期医嘱残留 IP-1017/在途 PENDING 计划残留 IP-1017")
    void confirmRejectsOngoingLongOrdersAndPendingPlans() {
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, REQUESTED_AT, null));

        // 追踪清单停留医嘱残留（CREATED 长期医嘱——人工处置未完成）
        when(orderMapper.selectCount(any())).thenReturn(2L);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_NOT_ALLOWED))
                .hasMessageContaining("非终态长期医嘱 2 条");

        // 在途执行计划残留（后续新增/漏网防御）
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectCount(any())).thenReturn(3L);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_NOT_ALLOWED))
                .hasMessageContaining("在途执行计划未清零");
        verifyNoInteractions(bedService, events);
    }

    @Test
    @DisplayName("确认守卫与并发面全集：IP-1018/IP-1007/IP-1022/带药生效零行/visit 与申请 CAS 零行/床位缺失/回读缺失")
    void confirmGuards() {
        OffsetDateTime settledAt = REQUESTED_AT;
        DischargeConfirmRequest req = new DischargeConfirmRequest(null, null, null);

        // 申请不存在
        when(requestMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.DISCHARGE_REQUEST_NOT_FOUND));

        // 操作者标识非数字（申请行有效——守卫先于状态校验）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, settledAt, null));
        OperatorContextHolder.set("abc");
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        OperatorContextHolder.set(String.valueOf(OPERATOR));

        // 关联就诊缺失（三重校验后取行——数据不一致防御）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, settledAt, null));
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(planMapper.selectCount(any())).thenReturn(0L);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 随访方式词表外（模块内直调防御——走到随访生成面的全链前置均满足）
        when(visitMapper.selectById(VISIT_PK))
                .thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED), dischargedVisitRow());
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(visitMapper.casDischarge(VISIT_ID, "1", String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casComplete(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(1);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, new DischargeConfirmRequest(null, "SMS", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID))
                .hasMessageContaining("followUpWay");

        // 带药放行生效时点落写零行（并发逻辑删窗口）
        MedicalOrder dischargeMed = orderRow(
                9200L, "MO2026092500100", OrderClass.STAT.getCode(), OrderStatus.CREATED, OrderType.DISCHARGE_MED);
        when(orderMapper.selectList(any())).thenReturn(List.of(dischargeMed));
        when(orderMapper.updateAuditBegin(eq("MO2026092500100"), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(0);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("生效时点落写零行");

        // visit CAS 零行（并发取消先行）
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(visitMapper.casDischarge(VISIT_ID, "1", String.valueOf(OPERATOR))).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("离院确认就诊状态并发冲突");

        // 申请 CAS 零行（并发迁移兜底）
        when(visitMapper.casDischarge(VISIT_ID, "1", String.valueOf(OPERATOR))).thenReturn(1);
        when(requestMapper.casComplete(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("离院确认申请状态并发冲突");

        // 在院无床位（数据不一致 fail-closed）
        when(requestMapper.casComplete(REQUEST_NO, String.valueOf(OPERATOR))).thenReturn(1);
        InpatientVisit noBed = visitRow(VisitStatus.DISCHARGE_REQUESTED);
        noBed.setCurrentBedId(null);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(noBed);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("无当前床位");

        // 回读缺失（并发逻辑删）
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED), null);
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("回读缺失");

        // 回读行缺出院时点（防御——CAS 后时点必在，脏数据定性冲突）
        when(visitMapper.selectById(VISIT_PK))
                .thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED), visitRow(VisitStatus.DISCHARGED));
        assertThatThrownBy(() -> service.confirm(REQUEST_NO, req))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT))
                .hasMessageContaining("回读缺失");
    }

    @Test
    @DisplayName("用例③挂账审批放行：BLOCKED→READY（审批单号记录——放行凭证留痕）")
    void arrearsApprovedFlipsBlockedToReady() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.BLOCKED, null, null));
        when(requestMapper.casApproveArrears(REQUEST_NO, "AP2026092500001", String.valueOf(OPERATOR)))
                .thenReturn(1);

        service.onArrearsApproved(VISIT_ID, "AP2026092500001");

        verify(requestMapper).casApproveArrears(REQUEST_NO, "AP2026092500001", String.valueOf(OPERATOR));
    }

    @Test
    @DisplayName("挂账审批消费幂等面：无在途申请直返/非 BLOCKED 态零行直返/就诊不可定位死信留痕")
    void arrearsApprovedIdempotentBranches() {
        // 无在途申请（在途中结算或无申请——info 直返）
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        when(requestMapper.selectOne(any())).thenReturn(null);
        service.onArrearsApproved(VISIT_ID, "AP2026092500001");
        verify(requestMapper, never()).casApproveArrears(any(), any(), any());

        // 非 BLOCKED 态零行（已达 READY 幂等直返）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        when(requestMapper.casApproveArrears(any(), any(), any())).thenReturn(0);
        service.onArrearsApproved(VISIT_ID, "AP2026092500001");

        // 载荷 visitId 不可定位（数据不一致——死信留痕口径 fail-closed）
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.onArrearsApproved("I2099010100001", "AP2026092500001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法定位住院就诊");
    }

    @Test
    @DisplayName("用例④结算完成标记：在途申请落 settlement_completed_at（信封时点透传）")
    void settlementCompletedMarksFlag() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.DISCHARGE_REQUESTED));
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        when(requestMapper.casMarkSettled(eq(REQUEST_NO), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);

        Instant settledAt = Instant.parse("2026-09-25T06:00:00Z");
        service.onSettlementCompleted(VISIT_ID, settledAt);

        ArgumentCaptor<OffsetDateTime> atCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(requestMapper).casMarkSettled(eq(REQUEST_NO), atCaptor.capture(), eq(String.valueOf(OPERATOR)));
        assertThat(atCaptor.getValue().toInstant()).isEqualTo(settledAt);
    }

    @Test
    @DisplayName("结算完成消费幂等面：无在途申请直返/已标记零行直返/操作者回退 system（消费线程无上下文）")
    void settlementCompletedIdempotentBranches() {
        // 无在途申请（在途中结算——非出院结算场景 info 直返）
        when(visitMapper.selectOne(any())).thenReturn(visitRow(VisitStatus.ADMITTED));
        when(requestMapper.selectOne(any())).thenReturn(null);
        service.onSettlementCompleted(VISIT_ID, Instant.now());
        verify(requestMapper, never()).casMarkSettled(any(), any(), any());

        // 已标记幂等（IS NULL 限定 CAS 零行）
        when(requestMapper.selectOne(any())).thenReturn(requestRow(DischargeRequestStatus.READY, null, null));
        when(requestMapper.casMarkSettled(any(), any(), any())).thenReturn(0);
        service.onSettlementCompleted(VISIT_ID, Instant.now());

        // 消费线程无登录上下文——操作者回退 system（与审计列默认同源）
        OperatorContextHolder.clear();
        when(requestMapper.casMarkSettled(any(), any(), any())).thenReturn(1);
        service.onSettlementCompleted(VISIT_ID, Instant.now());
        verify(requestMapper).casMarkSettled(any(), any(), eq("system"));
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    /** 在院/申请中就诊行夹具（currentBedId 在位——离院确认终末消毒流转对象）。 */
    private InpatientVisit visitRow(VisitStatus status) {
        InpatientVisit visit = new InpatientVisit();
        visit.setId(VISIT_PK);
        visit.setVisitId(VISIT_ID);
        visit.setPatientId(PATIENT_ID);
        visit.setStatus(status.getCode());
        visit.setCurrentWardId("W01");
        visit.setCurrentBedId(BED_ID);
        return visit;
    }

    /** 申请后就诊行夹具（discharge_requested_at=库端 now() 单测替身——事件载荷源）。 */
    private InpatientVisit requestedVisitRow() {
        InpatientVisit visit = visitRow(VisitStatus.DISCHARGE_REQUESTED);
        visit.setDischargeRequestedAt(REQUESTED_AT);
        return visit;
    }

    /** 出院后就诊行夹具（discharged_at=库端 now() 单测替身——随访日期与事件载荷源）。 */
    private InpatientVisit dischargedVisitRow() {
        InpatientVisit visit = visitRow(VisitStatus.DISCHARGED);
        visit.setDischargedAt(DISCHARGED_AT);
        return visit;
    }

    /** 医嘱行夹具（清理分野与带药放行取数面）。 */
    private MedicalOrder orderRow(long id, String orderNo, String orderClass, OrderStatus status, OrderType type) {
        MedicalOrder order = new MedicalOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setVisitId(VISIT_PK);
        order.setPatientId(PATIENT_ID);
        order.setOrderClass(orderClass);
        order.setOrderType(type.getCode());
        order.setStatus(status.getCode());
        return order;
    }

    /** 终态医嘱夹具（计划作废兜底覆盖面——COMPLETED 行也在就诊全集内）。 */
    private MedicalOrder completedOrder() {
        return orderRow(9100L, "MO2026092500009", OrderClass.STAT.getCode(), OrderStatus.COMPLETED, OrderType.LAB);
    }

    /**
     * 出院申请行夹具。
     *
     * @param status            申请状态
     * @param settlementAt      结算完成时点（null=未结算——用例⑥单条件拒绝载体）
     * @param approvalNo        挂账审批单号（null=未走挂账）
     */
    private DischargeRequest requestRow(DischargeRequestStatus status, OffsetDateTime settlementAt, String approvalNo) {
        DischargeRequest row = new DischargeRequest();
        row.setId(6001L);
        row.setRequestNo(REQUEST_NO);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setRequesterId(String.valueOf(OPERATOR));
        row.setRequestedAt(REQUESTED_AT);
        row.setExpectDischargeAt(REQUESTED_AT.plusDays(1));
        row.setDischargeWay("1");
        row.setClearanceResult("{\"stoppedLongCount\":0,\"trackedOrders\":[],\"cancelledPlanCount\":0}");
        row.setStatus(status.getCode());
        row.setSettlementCompletedAt(settlementAt);
        row.setApprovalNo(approvalNo);
        return row;
    }
}

package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.billing.api.SettlementSourceRefs;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.AppointmentMapper;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IChargingService;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

/**
 * 门诊收费编排服务单测（M03 FU-M03-07/08，Task 10 冻结用例集 11 例 + 守卫/分支补例）：单据精确
 * 放行（以端口反查 orderRefs/rxRefs 为唯一清单，visit 全量另单不动——用例 1/11 并列断言）、
 * order.charged 扇出载荷逐字（用例 2）、住院类型忽略（用例 3）、visit 回诊推进每迁必记（用例 4）、
 * 挂号费收费回填分发（用例 5，PAID⇒visit 锚在位定案①落点）、退费逆向逐单回滚+扇出（用例 6/
 * 无命中零发布 7）、处方作废回流（用例 8）、已发药镜像（用例 9）、重投幂等二次全跳过（用例 10）；
 * 补例承载锚缺失/0 元/visit 缺失/端口异常转译可读拒绝（W-20 关联面）与非在迁态跳过分支
 * （service.impl 包 LINE=1.00 门禁）。AFTER_COMMIT 的 MQ 出线时机归 OutpatientEventPublisherTest
 * 与集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class ChargingServiceImplTest {

    private static final String VISIT_ID = "O20260921000001";

    private static final String SETTLE_NO = "S20260920001";

    private static final long SETTLEMENT_ID = 501L;

    @Mock
    private ClinicOrderMapper clinicOrderMapper;

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private VisitStatusLogMapper visitStatusLogMapper;

    @Mock
    private AppointmentMapper appointmentMapper;

    @Mock
    private SettlementQueryPort settlementQueryPort;

    @Mock
    private IAppointmentService appointmentService;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<VisitStatusLog> statusLogCaptor;

    private IChargingService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次：申请单/visit 两实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClinicOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChargingServiceImpl(
                clinicOrderMapper, visitMapper, visitStatusLogMapper, settlementQueryPort, appointmentService, events);
    }

    // ---------------------------------------------------------------- 冻结用例集（11 例）

    @Test
    @DisplayName("结算放行单据精确：orderRefs=[OP…0001] 仅该单 CHARGED——visit 全量另单 CREATED 不动（断言核心）")
    void settlementMarksCoveredOrdersChargedByExactRefs() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of("OP20260920000001"), List.of()));
        when(clinicOrderMapper.casCharge("OP20260920000001", SETTLEMENT_ID)).thenReturn(1);

        service.onSettlementCompleted(settlementCompleted("OUT"));

        // 单据精确：仅反查清单内单据被放行+结算锚回填；visit 全量另单（OP…0002，CREATED）零触碰
        verify(clinicOrderMapper, times(1)).casCharge(anyString(), anyLong());
        verify(clinicOrderMapper).casCharge("OP20260920000001", SETTLEMENT_ID);
        verify(clinicOrderMapper, never()).casCharge(eq("OP20260920000002"), anyLong());
        // visit 已就诊中：无待缴费推进（他态为正常竞态）
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("结算放行扇出：order.charged 载荷组件逐字断言（rxNos 与端口返回逐字一致，绿通位 false）")
    void settlementFansOutChargedWithRxList() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(
                        SETTLEMENT_ID, List.of("OP20260920000001", "OP20260920000002"), List.of("RX20260920000001")));
        when(clinicOrderMapper.casCharge(anyString(), anyLong())).thenReturn(1);
        when(clinicOrderMapper.casRxRefCharged("RX20260920000001")).thenReturn(1);

        service.onSettlementCompleted(settlementCompleted("OUT"));

        // 载荷冻结断言（V204 id 25 record 七组件逐字；orderNos/rxNos=端口反查全集透传，非增量过滤面）
        verify(events).publishEvent(eventCaptor.capture());
        OutpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_ORDER_CHARGED);
        assertThat(event.payload()).isInstanceOf(com.fuyun.outpatient.api.OrderChargedPayload.class);
        var payload = (com.fuyun.outpatient.api.OrderChargedPayload) event.payload();
        assertThat(payload.settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(payload.settleNo()).isEqualTo(SETTLE_NO);
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.orderNos()).containsExactly("OP20260920000001", "OP20260920000002");
        assertThat(payload.rxNos()).containsExactly("RX20260920000001");
        assertThat(payload.greenChannelFlag()).isFalse();
    }

    @Test
    @DisplayName("住院结算类型忽略：settleType=IN 零写零发布零反查（住院分支归 M04 编排）")
    void settlementIgnoresInpatientType() {
        service.onSettlementCompleted(settlementCompleted("IN"));

        verifyNoInteractions(
                clinicOrderMapper, visitMapper, visitStatusLogMapper, settlementQueryPort, appointmentService, events);
    }

    @Test
    @DisplayName("结算回诊推进：visit PENDING_FEE→IN_CONSULT 状态机 CAS 命中+迁移日志每迁必记（operator=system）")
    void settlementAdvancesVisitPendingFeeBackToInConsult() {
        mockVisit(VisitStatus.PENDING_FEE);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of(), List.of()));
        when(visitMapper.casStatus(77L, "PENDING_FEE", "IN_CONSULT")).thenReturn(1);

        service.onSettlementCompleted(settlementCompleted("OUT"));

        // 状态机迁移+每迁必记（红线 5）：from/to/operator 断言
        verify(visitMapper).casStatus(77L, "PENDING_FEE", "IN_CONSULT");
        verify(visitStatusLogMapper).insert(statusLogCaptor.capture());
        VisitStatusLog statusLog = statusLogCaptor.getValue();
        assertThat(statusLog.getVisitId()).isEqualTo(VISIT_ID);
        assertThat(statusLog.getFromStatus()).isEqualTo(VisitStatus.PENDING_FEE);
        assertThat(statusLog.getToStatus()).isEqualTo(VisitStatus.IN_CONSULT);
        assertThat(statusLog.getOperator()).isEqualTo("system");
        // 空清单结算（纯挂号费面）：零放行零扇出
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("挂号费收费回填分发：settleType=OUT 委托 markRegistrationPaid（settleNo/settlementId/visitId 三参）")
    void settlementMarksRegistrationFeePaid() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of(), List.of()));

        service.onSettlementCompleted(settlementCompleted("OUT"));

        // PAID⇒visit 锚在位（Task 6 契约缝定案①）：分发以 visitId 为锚，回填在 IAppointmentService 收口
        verify(appointmentService).markRegistrationPaid(SETTLE_NO, SETTLEMENT_ID, VISIT_ID);
    }

    @Test
    @DisplayName("退费逆向回滚：orderRefs 两单双 CANCELLED+双 order.cancelled 扇出（reason=退费逆向终态确认）")
    void refundApprovedRollsBackChargedOrdersAndFansOutCancelled() {
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(
                        SETTLEMENT_ID, List.of("OP20260920000001", "OP20260920000002"), List.of("RX20260920000001")));
        when(clinicOrderMapper.selectOne(any()))
                .thenReturn(order(881L, "OP20260920000001", OrderStatus.CHARGED))
                .thenReturn(order(882L, "OP20260920000002", OrderStatus.CHARGED));
        when(clinicOrderMapper.casStatus(881L, "CHARGED", "CANCELLED")).thenReturn(1);
        when(clinicOrderMapper.casStatus(882L, "CHARGED", "CANCELLED")).thenReturn(1);

        service.onRefundApproved(refundApproved());

        verify(clinicOrderMapper).casStatus(881L, "CHARGED", "CANCELLED");
        verify(clinicOrderMapper).casStatus(882L, "CHARGED", "CANCELLED");
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        List<OutpatientDomainEvent> published = eventCaptor.getAllValues();
        assertThat(published).allSatisfy(event -> assertThat(event.eventType())
                .isEqualTo(OutpatientMessagingConstants.EVENT_ORDER_CANCELLED));
        var first = (com.fuyun.outpatient.api.OrderCancelledPayload)
                published.get(0).payload();
        var second = (com.fuyun.outpatient.api.OrderCancelledPayload)
                published.get(1).payload();
        assertThat(first.orderNo()).isEqualTo("OP20260920000001");
        assertThat(second.orderNo()).isEqualTo("OP20260920000002");
        assertThat(first.reason()).isEqualTo("退费逆向终态确认");
        assertThat(second.reason()).isEqualTo("退费逆向终态确认");
        assertThat(first.rxNos()).containsExactly("RX20260920000001");
        assertThat(first.patientId()).isEqualTo(9L);
        assertThat(first.visitId()).isEqualTo(VISIT_ID);
    }

    @Test
    @DisplayName("退费逆向无命中幂等：orderRefs 无本域单据——零写零发布（回执可重投）")
    void refundApprovedSkipsUnknownOrders() {
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of("OP20260929999999"), List.of()));
        when(clinicOrderMapper.selectOne(any())).thenReturn(null);

        service.onRefundApproved(refundApproved());

        verify(clinicOrderMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("处方作废回流：RX_REF ext_ref 命中三态 CAS 至 CANCELLED")
    void prescriptionCancelledMarksRxRefRowCancelled() {
        when(clinicOrderMapper.casCancelRxRef("RX20260920000001")).thenReturn(1);

        service.onPrescriptionCancelled(
                new PrescriptionCancelledPayload("RX20260920000001", "RX20260920000001", 9L, VISIT_ID, "医生站作废"));

        verify(clinicOrderMapper).casCancelRxRef("RX20260920000001");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("已发药镜像：dispense.completed 回流写 DISPENSED（派生展示面，状态机五值不变）")
    void dispenseCompletedMirrorsDispensedOnRxRef() {
        when(clinicOrderMapper.casMirrorDispensed("RX20260920000001")).thenReturn(1);

        service.onDispenseCompleted("RX20260920000001", "DF20260920001");

        verify(clinicOrderMapper).casMirrorDispensed("RX20260920000001");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("结算重投幂等：同 payload 二次投递 CAS 全 0 行重读 CHARGED info——零二次发布")
    void settlementIdempotentOnRedelivery() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(
                        SETTLEMENT_ID, List.of("OP20260920000001"), List.of("RX20260920000001")));
        when(clinicOrderMapper.casCharge("OP20260920000001", SETTLEMENT_ID)).thenReturn(1, 0);
        when(clinicOrderMapper.casRxRefCharged("RX20260920000001")).thenReturn(1, 0);
        // 重读定性：单据/引用行均已 CHARGED（首投已放行，同事务发布随首投 AFTER_COMMIT 出线）
        when(clinicOrderMapper.selectOne(any()))
                .thenReturn(order(881L, "OP20260920000001", OrderStatus.CHARGED))
                .thenReturn(order(881L, "OP20260920000001", OrderStatus.CHARGED));

        service.onSettlementCompleted(settlementCompleted("OUT"));
        service.onSettlementCompleted(settlementCompleted("OUT"));

        verify(clinicOrderMapper, times(2)).casCharge("OP20260920000001", SETTLEMENT_ID);
        verify(events, times(1)).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("结算放行引用行单据精确：rxRefs 命中 CREATED→CHARGED，未列引用行不动（与用例 1 并列断言）")
    void settlementMarksRxRefRowsChargedByExactRefs() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of(), List.of("RX20260920000001")));
        when(clinicOrderMapper.casRxRefCharged("RX20260920000001")).thenReturn(1);

        service.onSettlementCompleted(settlementCompleted("OUT"));

        // 引用行精确：仅反查清单内 rxNo 被放行；未列引用行（RX…9999）零触碰
        verify(clinicOrderMapper, times(1)).casRxRefCharged(anyString());
        verify(clinicOrderMapper).casRxRefCharged("RX20260920000001");
        verify(clinicOrderMapper, never()).casRxRefCharged("RX20260920999999");
        verify(events, times(1)).publishEvent(any(OutpatientDomainEvent.class));
    }

    // ---------------------------------------------------------------- 守卫/分支补例（LINE=1.00 门禁）

    @Test
    @DisplayName("结算锚缺失拒绝：settlementId=0 不合规帧抛可读 IllegalStateException（死信留痕，禁静默消费）")
    void settlementRejectsMissingAnchors() {
        SettlementCompletedPayload missing =
                new SettlementCompletedPayload(0L, SETTLE_NO, 9L, VISIT_ID, "OUT", "SELF_PAY", 12000L, 0L, 0L, 12000L);

        assertThatThrownBy(() -> service.onSettlementCompleted(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不合规");
        verifyNoInteractions(clinicOrderMapper, visitMapper, settlementQueryPort, appointmentService, events);
    }

    @Test
    @DisplayName("0 元结算拒绝：totalAmount=0 抛可读异常（W-20 澄清前沿用既有拒绝语义，不放开不收窄）")
    void settlementRejectsZeroAmount() {
        SettlementCompletedPayload zero = new SettlementCompletedPayload(
                SETTLEMENT_ID, SETTLE_NO, 9L, VISIT_ID, "OUT", "SELF_PAY", 0L, 0L, 0L, 0L);

        assertThatThrownBy(() -> service.onSettlementCompleted(zero))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 元");
        verifyNoInteractions(clinicOrderMapper, visitMapper, settlementQueryPort, appointmentService, events);
    }

    @Test
    @DisplayName("visit 缺失拒绝：就诊记录缺失抛可读异常（数据异常死信留痕，零写零反查）")
    void settlementRejectsWhenVisitMissing() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.onSettlementCompleted(settlementCompleted("OUT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("就诊记录缺失");
        verifyNoInteractions(clinicOrderMapper, settlementQueryPort, appointmentService, events);
    }

    @Test
    @DisplayName("反查端口异常转译：底层异常转可读 IllegalStateException（W-20 关联面，禁裸抛）")
    void settlementTranslatesSourceRefPortFailure() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> service.onSettlementCompleted(settlementCompleted("OUT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M13 端口异常");
        verify(clinicOrderMapper, never()).casCharge(anyString(), anyLong());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("放行非在迁态跳过：单据已作废（重读非 CHARGED）warn 跳过——零增量零扇出")
    void settlementSkipsOrderInNonPendingState() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of("OP20260920000001"), List.of()));
        when(clinicOrderMapper.casCharge("OP20260920000001", SETTLEMENT_ID)).thenReturn(0);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(881L, "OP20260920000001", OrderStatus.CANCELLED));

        service.onSettlementCompleted(settlementCompleted("OUT"));

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("引用行放行非在迁态跳过：引用行已作废（重读非 CHARGED）warn 跳过——零增量零扇出")
    void settlementSkipsRxRefInNonCreatedState() {
        mockVisit(VisitStatus.IN_CONSULT);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of(), List.of("RX20260920000001")));
        when(clinicOrderMapper.casRxRefCharged("RX20260920000001")).thenReturn(0);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(881L, "OP20260920000001", OrderStatus.PENDING_FEE));

        service.onSettlementCompleted(settlementCompleted("OUT"));

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("visit 回诊推进 CAS 落败：并发已迁移 warn 跳过且不写迁移日志")
    void visitAdvanceSkipsOnCasMiss() {
        mockVisit(VisitStatus.PENDING_FEE);
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of(), List.of()));
        when(visitMapper.casStatus(77L, "PENDING_FEE", "IN_CONSULT")).thenReturn(0);

        service.onSettlementCompleted(settlementCompleted("OUT"));

        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
    }

    @Test
    @DisplayName("退费逆向非 CHARGED 跳过：单据处于待缴费态（逆向仅在回执后 CHARGED 态）零发布")
    void refundApprovedSkipsNonChargedOrder() {
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of("OP20260920000001"), List.of()));
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(881L, "OP20260920000001", OrderStatus.PENDING_FEE));

        service.onRefundApproved(refundApproved());

        verify(clinicOrderMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("退费逆向 CAS 落败：并发已迁移 warn 跳过零发布（幂等收敛）")
    void refundApprovedSkipsOnCasMiss() {
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenReturn(new SettlementSourceRefs(SETTLEMENT_ID, List.of("OP20260920000001"), List.of()));
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(881L, "OP20260920000001", OrderStatus.CHARGED));
        when(clinicOrderMapper.casStatus(881L, "CHARGED", "CANCELLED")).thenReturn(0);

        service.onRefundApproved(refundApproved());

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("退费逆向端口异常转译：底层异常转可读 IllegalStateException，零写零发布")
    void refundApprovedTranslatesSourceRefPortFailure() {
        when(settlementQueryPort.sourceRefsOfSettlement(SETTLEMENT_ID))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> service.onRefundApproved(refundApproved()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M13 端口异常");
        verify(clinicOrderMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("处方作废回流缺锚拒绝：rxNo 空白抛可读 IllegalStateException（禁静默消费）")
    void prescriptionCancelledRejectsBlankRxNo() {
        assertThatThrownBy(() -> service.onPrescriptionCancelled(
                        new PrescriptionCancelledPayload("RX20260920000001", "", 9L, VISIT_ID, "医生站作废")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rxNo");
        verifyNoInteractions(clinicOrderMapper, events);
    }

    @Test
    @DisplayName("处方作废回流幂等：无在迁引用行命中 0 行 info 跳过")
    void prescriptionCancelledSkipsWhenNoMutableRow() {
        when(clinicOrderMapper.casCancelRxRef("RX20260920000001")).thenReturn(0);

        service.onPrescriptionCancelled(
                new PrescriptionCancelledPayload("RX20260920000001", "RX20260920000001", 9L, VISIT_ID, "医生站作废"));

        verify(clinicOrderMapper).casCancelRxRef("RX20260920000001");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("已发药镜像缺锚拒绝与幂等跳过：rxNo 空白抛异常；非空档 0 行 info 跳过")
    void dispenseCompletedRejectsBlankRxNoAndSkipsOccupiedMirror() {
        assertThatThrownBy(() -> service.onDispenseCompleted("", "DF20260920001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rxNo");
        verifyNoInteractions(clinicOrderMapper);

        when(clinicOrderMapper.casMirrorDispensed("RX20260920000001")).thenReturn(0);
        service.onDispenseCompleted("RX20260920000001", "DF20260920001");
        verify(clinicOrderMapper).casMirrorDispensed("RX20260920000001");
    }

    @Test
    @DisplayName("退药镜像单调迁移：fullReturn 双分支目标态逐字（整单 FULL/部分 PART）")
    void dispenseReturnedMirrorsPartAndFullReturn() {
        when(clinicOrderMapper.casMirrorReturned("RX20260920000001", "FULL_RETURNED"))
                .thenReturn(1);
        when(clinicOrderMapper.casMirrorReturned("RX20260920000002", "PART_RETURNED"))
                .thenReturn(1);

        service.onDispenseReturned("RX20260920000001", true, "DF20260920001");
        service.onDispenseReturned("RX20260920000002", false, "DF20260920002");

        verify(clinicOrderMapper).casMirrorReturned("RX20260920000001", "FULL_RETURNED");
        verify(clinicOrderMapper).casMirrorReturned("RX20260920000002", "PART_RETURNED");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("退药镜像幂等与缺锚：同值重投 0 行 info 跳过；rxNo 空白抛可读异常")
    void dispenseReturnedSkipsOnNoopAndRejectsBlankRxNo() {
        when(clinicOrderMapper.casMirrorReturned("RX20260920000001", "PART_RETURNED"))
                .thenReturn(0);
        service.onDispenseReturned("RX20260920000001", false, "DF20260920001");
        verify(clinicOrderMapper).casMirrorReturned("RX20260920000001", "PART_RETURNED");

        assertThatThrownBy(() -> service.onDispenseReturned(" ", true, "DF20260920001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rxNo");
    }

    // ---------------------------------------------------------------- 替身构造

    /**
     * 结算完成载荷替身（V605 id 19 十组件；金额 120 元自付）。
     *
     * @param settleType 结算类型（OUT/IN）
     * @return 载荷替身，非空
     */
    private static SettlementCompletedPayload settlementCompleted(String settleType) {
        return new SettlementCompletedPayload(
                SETTLEMENT_ID, SETTLE_NO, 9L, VISIT_ID, settleType, "SELF_PAY", 12000L, 0L, 0L, 12000L);
    }

    /**
     * 退费回执载荷替身（V605 id 20 七组件）。
     *
     * @return 载荷替身，非空
     */
    private static RefundApprovedPayload refundApproved() {
        return new RefundApprovedPayload(9001L, "R9001", SETTLEMENT_ID, 9L, 12000L, "SETTLED_REFUND", true);
    }

    /**
     * 就诊记录替身（id=77，patientId=9，visitId 锚）。
     *
     * @param status 就诊状态
     */
    private void mockVisit(VisitStatus status) {
        Visit visit = new Visit();
        visit.setId(77L);
        visit.setVisitId(VISIT_ID);
        visit.setPatientId(9L);
        visit.setStatus(status);
        when(visitMapper.selectOne(any())).thenReturn(visit);
    }

    /**
     * 申请单替身（visitId/patientId 锚与 visit 一致，EXAM 单）。
     *
     * @param id      主键
     * @param orderNo 申请单号
     * @param status  单据状态
     * @return 申请单替身，非空
     */
    private static ClinicOrder order(long id, String orderNo, OrderStatus status) {
        ClinicOrder order = new ClinicOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setVisitId(VISIT_ID);
        order.setPatientId(9L);
        order.setOrderType(OrderType.EXAM);
        order.setStatus(status);
        return order;
    }
}

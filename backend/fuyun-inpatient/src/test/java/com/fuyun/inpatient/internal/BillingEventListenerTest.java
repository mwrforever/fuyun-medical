package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.service.AdmissionService;
import com.fuyun.inpatient.service.DischargeService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M13 计费联动消费监听器单测：settlement.completed（settleType=IN 出院结算分支才驱动标记，
 * OUT 门诊结算直返）/arrears.approved（visitId/approvalNo 逐字段透传放行服务）两业务体的
 * 载荷解析与守卫（缺 visitId/缺 approvalNo 不合规帧抛出死信留痕）；deposit.changed 欠费
 * 标识刷新面（Task 10 落地：visitId/balance 逐字段透传 AdmissionService——阈值裁决与 CAS
 * 归服务层，缺 visitId/缺 balance 不合规帧抛出死信留痕）。业务体为包级 handle 方法，
 * @RabbitListener 入口仅做 consume 委托（模板三段式幂等归 IdempotentConsumerSupport，IT 面
 * 验证），单测直驱 handle 等价路径（PharmacyAuditReplyListenerTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class BillingEventListenerTest {

    /** 编排主体 I 型 14 位就诊号（载荷 visitId 口径） */
    private static final String VISIT_ID = "I2026092500001";

    /** 挂账审批单号（放行凭证） */
    private static final String APPROVAL_NO = "AP2026092500001";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private DischargeService dischargeService;

    @Mock
    private AdmissionService admissionService;

    /** 构造 billing 回执信封（producer=billing，载荷 JSON 直构）。 */
    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new EventEnvelope(
                "ev-billing-1",
                Instant.now(Clock.systemUTC()),
                "billing",
                eventType,
                "1",
                "trace-1",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("settlement.completed 住院结算分支：settleType=IN 驱动结算标记（visitId+信封时点透传）")
    void settlementCompletedInBranchDelegatesMarking() throws Exception {
        EventEnvelope envelope = envelope(
                "billing.settlement.completed",
                "{\"settlementId\":9001,\"settleNo\":\"S1\",\"patientId\":1001,\"visitId\":\"" + VISIT_ID
                        + "\",\"settleType\":\"IN\",\"payerType\":\"CITY_INS\"}");

        new BillingEventListener(consumerSupport, dischargeService, admissionService)
                .handleSettlementCompleted(envelope);

        verify(dischargeService).onSettlementCompleted(eq(VISIT_ID), eq(envelope.occurredAt()));
    }

    @Test
    @DisplayName("settlement.completed 门诊结算分支：settleType=OUT 直返（出院标记服务零触达）")
    void settlementCompletedOutBranchSkipsDischargeMarking() throws Exception {
        new BillingEventListener(consumerSupport, dischargeService, admissionService)
                .handleSettlementCompleted(envelope(
                        "billing.settlement.completed",
                        "{\"settlementId\":9002,\"visitId\":\"O2026092500002\",\"settleType\":\"OUT\"}"));

        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
    }

    @Test
    @DisplayName("arrears.approved：visitId/approvalNo 逐字段透传挂账放行服务")
    void arrearsApprovedDelegatesRelease() throws Exception {
        new BillingEventListener(consumerSupport, dischargeService, admissionService)
                .handleArrearsApproved(envelope(
                        "billing.arrears.approved",
                        "{\"visitId\":\"" + VISIT_ID + "\",\"approvalNo\":\"" + APPROVAL_NO + "\"}"));

        verify(dischargeService).onArrearsApproved(VISIT_ID, APPROVAL_NO);
    }

    @Test
    @DisplayName("不合规帧守卫：缺 visitId（三回执）与缺 approvalNo/缺 balance 抛 IllegalStateException 死信留痕")
    void rejectsMalformedPayloads() throws Exception {
        BillingEventListener listener = new BillingEventListener(consumerSupport, dischargeService, admissionService);

        // 结算完成回执缺 visitId
        assertThatThrownBy(() -> listener.handleSettlementCompleted(
                        envelope("billing.settlement.completed", "{\"settleType\":\"IN\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 visitId");
        // 挂账放行回执缺 visitId
        assertThatThrownBy(() -> listener.handleArrearsApproved(
                        envelope("billing.arrears.approved", "{\"approvalNo\":\"AP1\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 visitId");
        // 挂账放行回执缺 approvalNo（放行凭证必附）
        assertThatThrownBy(() -> listener.handleArrearsApproved(
                        envelope("billing.arrears.approved", "{\"visitId\":\"" + VISIT_ID + "\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 approvalNo");
        // 押金变动回执缺 visitId
        assertThatThrownBy(
                        () -> listener.handleDepositChanged(envelope("billing.deposit.changed", "{\"balance\":50000}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 visitId");
        // 押金变动回执缺 balance（欠费判定基准必附）
        assertThatThrownBy(() -> listener.handleDepositChanged(
                        envelope("billing.deposit.changed", "{\"visitId\":\"" + VISIT_ID + "\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 balance");
        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
        verify(dischargeService, never()).onArrearsApproved(anyString(), anyString());
        verify(admissionService, never()).onDepositChanged(anyString(), anyLong());
    }

    @Test
    @DisplayName("deposit.changed 欠费标识面：visitId/balance 逐字段透传刷新服务（阈值裁决归服务层）")
    void depositChangedDelegatesArrearsFlagRefresh() throws Exception {
        new BillingEventListener(consumerSupport, dischargeService, admissionService)
                .handleDepositChanged(envelope(
                        "billing.deposit.changed",
                        "{\"accountId\":71,\"patientId\":1001,\"visitId\":\"" + VISIT_ID
                                + "\",\"balance\":-30000,\"status\":\"ARREARS\"}"));

        // 余额原值透传（分，GC18 仅为事件载荷消费）；status（billing 侧预警）不采信——M04 本地阈值独立裁决
        verify(admissionService).onDepositChanged(VISIT_ID, -30000L);
        // 出院放行两服务面零触达（押金面与出院放行无涉）
        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
        verify(dischargeService, never()).onArrearsApproved(anyString(), anyString());
    }
}

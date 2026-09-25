package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
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
 * 载荷解析与守卫（缺 visitId/缺 approvalNo 不合规帧抛出死信留痕）；deposit.changed 队列承载
 * 面（消费逻辑归 Task 10——消息经幂等三段式消费不抛出）。业务体为包级 handle 方法，
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

        new BillingEventListener(consumerSupport, dischargeService).handleSettlementCompleted(envelope);

        verify(dischargeService).onSettlementCompleted(eq(VISIT_ID), eq(envelope.occurredAt()));
    }

    @Test
    @DisplayName("settlement.completed 门诊结算分支：settleType=OUT 直返（出院标记服务零触达）")
    void settlementCompletedOutBranchSkipsDischargeMarking() throws Exception {
        new BillingEventListener(consumerSupport, dischargeService)
                .handleSettlementCompleted(envelope(
                        "billing.settlement.completed",
                        "{\"settlementId\":9002,\"visitId\":\"O2026092500002\",\"settleType\":\"OUT\"}"));

        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
    }

    @Test
    @DisplayName("arrears.approved：visitId/approvalNo 逐字段透传挂账放行服务")
    void arrearsApprovedDelegatesRelease() throws Exception {
        new BillingEventListener(consumerSupport, dischargeService)
                .handleArrearsApproved(envelope(
                        "billing.arrears.approved",
                        "{\"visitId\":\"" + VISIT_ID + "\",\"approvalNo\":\"" + APPROVAL_NO + "\"}"));

        verify(dischargeService).onArrearsApproved(VISIT_ID, APPROVAL_NO);
    }

    @Test
    @DisplayName("不合规帧守卫：缺 visitId（两回执）与缺 approvalNo 抛 IllegalStateException 死信留痕")
    void rejectsMalformedPayloads() throws Exception {
        BillingEventListener listener = new BillingEventListener(consumerSupport, dischargeService);

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
        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
        verify(dischargeService, never()).onArrearsApproved(anyString(), anyString());
    }

    @Test
    @DisplayName("deposit.changed 队列承载面：消息正常消费不抛出（欠费标识刷新逻辑归 Task 10）")
    void depositChangedConsumesWithoutBusinessSide() throws Exception {
        EventEnvelope envelope = envelope(
                "billing.deposit.changed",
                "{\"accountId\":71,\"patientId\":1001,\"visitId\":\"" + VISIT_ID
                        + "\",\"balance\":50000,\"status\":\"ARREARS\"}");

        BillingEventListener listener = new BillingEventListener(consumerSupport, dischargeService);

        assertThatCode(() -> listener.handleDepositChanged(envelope)).doesNotThrowAnyException();
        // Task 10 前零业务触达（出院放行面与押金面无涉）
        assertThat(envelope.eventType()).isEqualTo("billing.deposit.changed");
        verifyNoDischargeInteractions();
    }

    /** 出院放行两服务面零触达断言（deposit.changed 承载面专属）。 */
    private void verifyNoDischargeInteractions() {
        verify(dischargeService, never()).onSettlementCompleted(anyString(), any());
        verify(dischargeService, never()).onArrearsApproved(anyString(), anyString());
    }
}

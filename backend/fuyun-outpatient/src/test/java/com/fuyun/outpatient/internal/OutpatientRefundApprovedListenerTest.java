package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IAppointmentService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 退费回执监听器单测（billing.refund.approved 消费侧，Task 6）：合规七组件载荷解析委托与缺
 * settlementId 不合规帧显式拒绝（死信留痕）。业务体为包级 handle 方法，@RabbitListener 入口仅做
 * consume 委托（模板三段式已在 IT 面验证，BillingPharmacyOccupyListenerTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientRefundApprovedListenerTest {

    @Mock
    private IAppointmentService appointmentService;

    /** 信封替身（按线格式 JSON 构造；Long 以 string 承载与 JacksonLongToStringConfig 同源） */
    private EventEnvelope envelope(String payloadJson) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "billing",
                OutpatientMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("合规回执：解析七组件并委托 confirmRefundedCancel（回执驱动退号终态）")
    void handleDelegatesWellFormedReceiptToService() throws Exception {
        new OutpatientRefundApprovedListener(null, appointmentService)
                .handleRefundApproved(envelope("{\"refundId\":\"9001\",\"refundNo\":\"R9001\",\"settlementId\":\"501\","
                        + "\"patientId\":\"9\",\"amount\":\"5000\",\"refundType\":\"DAY_CORRECTION\","
                        + "\"autoApproved\":true}"));

        verify(appointmentService).confirmRefundedCancel(any());
    }

    @Test
    @DisplayName("载荷缺 settlementId：不合规帧抛 IllegalStateException（死信留痕，禁静默丢回执）")
    void rejectEnvelopeWithoutSettlement() throws Exception {
        assertThatThrownBy(() -> new OutpatientRefundApprovedListener(null, appointmentService)
                        .handleRefundApproved(envelope("{\"refundId\":\"9001\",\"refundNo\":\"R9001\"}")))
                .isInstanceOf(IllegalStateException.class);
        verify(appointmentService, never()).confirmRefundedCancel(any());
    }
}

package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IClinicOrderService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 缴费回执监听器单测（billing.fee.created 消费侧，Task 8）：billingKey 五段解析与开单计费点
 * （ORDER_CONFIRMED）守卫委托、非开单计费点 info 跳过、billingKey 不合规帧显式拒绝（死信留痕）。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（模板三段式已在 IT 面验证，
 * OutpatientRefundApprovedListenerTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientFeeCreatedListenerTest {

    @Mock
    private IClinicOrderService clinicOrderService;

    /** 信封替身（按线格式 JSON 构造；billingKey 五段=patientId|sourceRef|trigger|itemId|billingDate） */
    private EventEnvelope envelope(String billingKey) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "billing",
                OutpatientMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED,
                "1",
                "it-trace",
                new ObjectMapper().readTree("{\"billingKey\":\"" + billingKey + "\",\"feeId\":\"6001\"}"));
    }

    @Test
    @DisplayName("开单计费点回执：sourceRef 段解析为申请单号委托 markPendingFee")
    void handleDelegatesOrderConfirmedToService() throws Exception {
        new OutpatientFeeCreatedListener(null, clinicOrderService)
                .handleFeeCreated(envelope("9|" + "OP20260921000001" + "|ORDER_CONFIRMED|77|2026-09-21"));

        verify(clinicOrderService).markPendingFee("OP20260921000001");
    }

    @Test
    @DisplayName("非开单计费点（处方生效）：info 跳过且零业务委托")
    void handleSkipsNonOrderConfirmedTrigger() throws Exception {
        new OutpatientFeeCreatedListener(null, clinicOrderService)
                .handleFeeCreated(envelope("9|RX20260921000001|PRESCRIPTION_EFFECTIVE|88|2026-09-21"));

        verifyNoInteractions(clinicOrderService);
    }

    @Test
    @DisplayName("billingKey 不合规（缺段）：不合规帧抛 IllegalStateException（死信留痕，禁静默丢弃）")
    void rejectEnvelopeWithMalformedBillingKey() throws Exception {
        assertThatThrownBy(() -> new OutpatientFeeCreatedListener(null, clinicOrderService)
                        .handleFeeCreated(envelope("9|OP20260921000001")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(clinicOrderService);
    }
}

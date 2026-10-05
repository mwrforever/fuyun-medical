package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * （ORDER_CONFIRMED）守卫委托、非开单计费点 info 跳过、billingKey 不合规帧显式拒绝（死信留痕）、
 * 住院行（visitType=IN）跳过申请单推进（W-67b 净解——sourceRef 语义双载死信终结）。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（模板三段式已在 IT 面验证，
 * OutpatientRefundApprovedListenerTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientFeeCreatedListenerTest {

    @Mock
    private IClinicOrderService clinicOrderService;

    /** 信封替身（按线格式 JSON 构造；billingKey 五段=patientId|sourceRef|trigger|itemId|billingDate） */
    private EventEnvelope envelope(String billingKey) throws Exception {
        return envelope(billingKey, null);
    }

    /**
     * 信封替身（可注入 visitType 组件）：visitType=null 构造旧版本事件帧（V605 原九组件无该字段，
     * 消费端 path().asText("") 兜底空串走原路径）；非空构造 W-67b 后十组件新帧。
     */
    private EventEnvelope envelope(String billingKey, String visitType) throws Exception {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("billingKey", billingKey).put("feeId", "6001").put("feeNo", "FEE-IT-6001");
        if (visitType != null) {
            payload.put("visitType", visitType);
        }
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "billing",
                OutpatientMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED,
                "1",
                "it-trace",
                payload);
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
    @DisplayName("住院行 fee.created（visitType=IN）：跳过申请单推进不抛（W-67b 净解）")
    void skipsInpatientFeeCreatedWithoutClinicOrderAdvance() throws Exception {
        // 住院医嘱计费帧：billingKey 仍五段合法（sourceRef=住院医嘱号，trigger=ORDER_CONFIRMED
        // 命中死信路径）——visitType=IN 判别跳过，不查 clinic_order 不抛 ISE
        new OutpatientFeeCreatedListener(null, clinicOrderService)
                .handleFeeCreated(envelope("9|" + "IO20261005000001" + "|ORDER_CONFIRMED|77|2026-10-05", "IN"));

        verifyNoInteractions(clinicOrderService);
    }

    @Test
    @DisplayName("门诊行（visitType=OUT）与旧帧（无 visitType 组件）：照常推进申请单（原路径兼容）")
    void outpatientAndLegacyFramesStillAdvanceClinicOrder() throws Exception {
        // 新帧 visitType=OUT：照常 markPendingFee（门诊申请单号语义不变）
        new OutpatientFeeCreatedListener(null, clinicOrderService)
                .handleFeeCreated(envelope("9|" + "OP20260921000001" + "|ORDER_CONFIRMED|77|2026-09-21", "OUT"));
        verify(clinicOrderService).markPendingFee("OP20260921000001");

        // 旧版本帧（V605 九组件无 visitType）：asText("") 兜底空串≠IN 走原路径（兼容不破坏）
        new OutpatientFeeCreatedListener(null, clinicOrderService)
                .handleFeeCreated(envelope("9|" + "OP20260921000002" + "|ORDER_CONFIRMED|77|2026-09-21"));
        verify(clinicOrderService).markPendingFee("OP20260921000002");
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

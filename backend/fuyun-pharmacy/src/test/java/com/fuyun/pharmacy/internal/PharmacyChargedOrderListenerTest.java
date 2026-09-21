package com.fuyun.pharmacy.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.service.IDispenseService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 缴费放行监听器单测（PR-5 Task 11 回切）：payload 读 rxNos 数组单据精确放行（裁决 4）、
 * visitId/rxNos 双字段不合规帧守卫、空清单（纯检查/检验结算）info 跳过零放行。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（PharmacyMasterDataListenerTest
 * 同款单测形态），单测直驱 handle 等价路径。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyChargedOrderListenerTest {

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IDispenseService dispenseService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 信封手工构造（本域监听器单测同款形态）：载荷按 V204 id 25 冻结组件名承载 */
    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "outpatient",
                "outpatient.order.charged",
                "1",
                "it-trace",
                payload);
    }

    @Test
    @DisplayName("charged 放行：payload 读 rxNos 数组逐单精确传递（visitId 字段守卫维持）")
    void handleOrderChargedPassesRxNosToService() {
        PharmacyChargedOrderListener listener = new PharmacyChargedOrderListener(consumerSupport, dispenseService);
        var payload = objectMapper.createObjectNode();
        payload.put("settlementId", 1950000000000001L)
                .put("settleNo", "S20260918000001")
                .put("patientId", 700101L)
                .put("visitId", "O2026091800001");
        payload.putArray("orderNos").add("ORD-1");
        payload.putArray("rxNos").add("R20260918000001").add("R20260918000002");

        listener.handleOrderCharged(envelope(payload));

        verify(dispenseService).releaseByRxNos(List.of("R20260918000001", "R20260918000002"));
    }

    @Test
    @DisplayName("charged 空清单容忍：rxNos=[]（该结算无药品行）info 跳过零放行")
    void chargedListenerToleratesEmptyRxNos() {
        PharmacyChargedOrderListener listener = new PharmacyChargedOrderListener(consumerSupport, dispenseService);
        var payload = objectMapper.createObjectNode();
        payload.put("visitId", "O2026091800001");
        payload.putArray("rxNos");

        listener.handleOrderCharged(envelope(payload));

        verifyNoInteractions(dispenseService);
    }

    @Test
    @DisplayName("charged 守卫回归：缺 visitId 不合规帧显式抛出进死信留痕（V204 id 25 冻结契约）")
    void chargedListenerStillRejectsMissingVisitId() {
        PharmacyChargedOrderListener listener = new PharmacyChargedOrderListener(consumerSupport, dispenseService);
        var payload = objectMapper.createObjectNode();
        payload.put("patientId", 700101L);
        payload.putArray("rxNos").add("R20260918000001");

        assertThatThrownBy(() -> listener.handleOrderCharged(envelope(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不合规");
        verifyNoInteractions(dispenseService);
    }

    @Test
    @DisplayName("charged 守卫回归：rxNos 非数组不合规帧显式抛出（双字段校验）")
    void chargedListenerRejectsNonArrayRxNos() {
        PharmacyChargedOrderListener listener = new PharmacyChargedOrderListener(consumerSupport, dispenseService);
        var payload = objectMapper.createObjectNode();
        payload.put("visitId", "O2026091800001").put("rxNos", "R20260918000001");

        assertThatThrownBy(() -> listener.handleOrderCharged(envelope(payload)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(dispenseService);
    }

    @Test
    @DisplayName("@RabbitListener 入口仅做 consume 委托（三段式标准形态，业务体不重复触达）")
    void onOrderChargedDelegatesToConsumerSupport() {
        PharmacyChargedOrderListener listener = new PharmacyChargedOrderListener(consumerSupport, dispenseService);

        listener.onOrderCharged(new org.springframework.amqp.core.Message(
                new byte[0], new org.springframework.amqp.core.MessageProperties()));

        verify(consumerSupport).consume(any(org.springframework.amqp.core.Message.class), any());
    }
}

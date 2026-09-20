package com.fuyun.billing.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * billing 占用回写监听器单测：completed→DISPENSED、returned(fullReturn)→NONE、
 * 部分退保持、缺 rxNo 不合规帧抛出（死信留痕）。业务体为包级 handle 方法，@RabbitListener
 * 入口仅做 consume 委托（模板三段式已在 IT 面验证），单测直驱 handle 等价路径。
 */
@ExtendWith(MockitoExtension.class)
class BillingPharmacyOccupyListenerTest {

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private com.fuyun.billing.mapper.FeeRecordMapper feeRecordMapper;

    private com.fuyun.common.messaging.EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new com.fuyun.common.messaging.EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "pharmacy",
                eventType,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("completed：按 rxNo 条件回写 DISPENSED（CAS 谓词 NONE→DISPENSED）")
    void handleCompletedMarksFeesDispensed() throws Exception {
        new BillingPharmacyOccupyListener(consumerSupport, feeRecordMapper)
                .handleCompleted(envelope(
                        "pharmacy.dispense.completed",
                        "{\"rxNo\":\"R20260918000001\",\"dispenseNo\":\"D1\",\"lines\":[]}"));

        verify(feeRecordMapper).casMarkDispensed("R20260918000001");
    }

    @Test
    @DisplayName("returned 全退：占用回退 NONE（退费硬前置解锁）")
    void handleReturnedReleasesOccupancyOnFullReturn() throws Exception {
        new BillingPharmacyOccupyListener(consumerSupport, feeRecordMapper)
                .handleReturned(envelope("pharmacy.dispense.returned", "{\"rxNo\":\"R1\",\"fullReturn\":true}"));

        verify(feeRecordMapper).casReleaseDispense("R1");
    }

    @Test
    @DisplayName("returned 部分退：保持 DISPENSED 不回退（余量仍在患者侧）")
    void handleReturnedKeepsOccupancyOnPartialReturn() throws Exception {
        new BillingPharmacyOccupyListener(consumerSupport, feeRecordMapper)
                .handleReturned(envelope("pharmacy.dispense.returned", "{\"rxNo\":\"R1\",\"fullReturn\":false}"));

        verify(feeRecordMapper, never()).casReleaseDispense(any());
    }

    @Test
    @DisplayName("载荷缺 rxNo：不合规帧抛 IllegalStateException（死信留痕，禁静默丢占用）")
    void rejectEnvelopeWithoutRxNo() throws Exception {
        assertThatThrownBy(() -> new BillingPharmacyOccupyListener(consumerSupport, feeRecordMapper)
                        .handleCompleted(envelope("pharmacy.dispense.completed", "{\"dispenseNo\":\"D1\"}")))
                .isInstanceOf(IllegalStateException.class);
        verify(feeRecordMapper, never()).casMarkDispensed(any());
    }
}

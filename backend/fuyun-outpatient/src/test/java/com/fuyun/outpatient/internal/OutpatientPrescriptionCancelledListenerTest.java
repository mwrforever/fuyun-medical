package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 处方作废回流监听器单测（pharmacy.prescription.cancelled 消费侧，Task 10）：五组件解析委托断言。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（rxNo 锚守卫归 IChargingService）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientPrescriptionCancelledListenerTest {

    @Mock
    private IChargingService chargingService;

    /** 信封替身（按线格式 JSON 构造；Long 以 string 承载与 JacksonLongToStringConfig 同源） */
    private EventEnvelope envelope(String payloadJson) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "pharmacy",
                OutpatientMessagingConstants.EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("合规回流：解析五组件并委托 onPrescriptionCancelled（rxNo 锚逐字）")
    void handleDelegatesWellFormedFlowToService() throws Exception {
        new OutpatientPrescriptionCancelledListener(null, chargingService)
                .handlePrescriptionCancelled(envelope("{\"prescriptionId\":\"RX20260920000001\","
                        + "\"rxNo\":\"RX20260920000001\",\"patientId\":\"9\",\"visitId\":\"O20260921000001\","
                        + "\"reason\":\"医生站作废\"}"));

        ArgumentCaptor<PrescriptionCancelledPayload> captor =
                ArgumentCaptor.forClass(PrescriptionCancelledPayload.class);
        verify(chargingService).onPrescriptionCancelled(captor.capture());
        PrescriptionCancelledPayload payload = captor.getValue();
        assertThat(payload.rxNo()).isEqualTo("RX20260920000001");
        assertThat(payload.visitId()).isEqualTo("O20260921000001");
        assertThat(payload.reason()).isEqualTo("医生站作废");
    }

    @Test
    @DisplayName("回流缺 rxNo：解析委托不拦截（由业务侧锚守卫统一可读拒绝）")
    void handleDelegatesMalformedAnchorsToServiceGuard() throws Exception {
        new OutpatientPrescriptionCancelledListener(null, chargingService)
                .handlePrescriptionCancelled(envelope("{\"visitId\":\"O20260921000001\"}"));

        verify(chargingService).onPrescriptionCancelled(any());
    }
}

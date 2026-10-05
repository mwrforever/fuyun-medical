package com.fuyun.outpatient.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 发药完成回流监听器单测（pharmacy.dispense.completed 消费侧，Task 10）：编排所消费组件
 * （rxNo/dispenseNo）解析委托断言（lines 组件归 M13 占用面不消费不解析）、住院行判别子跳过
 * （W-67a）。业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（rxNo 锚守卫归
 * IChargingService）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientDispenseCompletedListenerTest {

    @Mock
    private IChargingService chargingService;

    /** 信封替身（按线格式 JSON 构造；Long 以 string 承载与 JacksonLongToStringConfig 同源） */
    private EventEnvelope envelope(String payloadJson) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "pharmacy",
                OutpatientMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_COMPLETED,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("合规回流：解析 rxNo/dispenseNo 并委托 onDispenseCompleted（lines 不解析不透传）")
    void handleDelegatesConsumedFieldsToService() throws Exception {
        new OutpatientDispenseCompletedListener(null, chargingService)
                .handleDispenseCompleted(envelope("{\"dispenseNo\":\"DF20260920001\",\"rxNo\":\"RX20260920000001\","
                        + "\"patientId\":\"9\",\"visitId\":\"O20260921000001\",\"dispenseType\":\"WINDOW\","
                        + "\"lines\":[]}"));

        verify(chargingService).onDispenseCompleted("RX20260920000001", "DF20260920001");
    }

    @Test
    @DisplayName("回流缺 rxNo：解析委托不拦截（由业务侧锚守卫统一可读拒绝）")
    void handleDelegatesMalformedAnchorsToServiceGuard() throws Exception {
        new OutpatientDispenseCompletedListener(null, chargingService)
                .handleDispenseCompleted(envelope("{\"dispenseNo\":\"DF20260920001\"}"));

        verify(chargingService).onDispenseCompleted(any(), any());
    }

    @Test
    @DisplayName("住院行 dispense.completed（rxNo/prescriptionId 双缺席+m04OrderNo 在位）：收费链跳过不抛（住院计费归 M13 路径）")
    void skipsInpatientDispenseCompletedWithoutChargingDelegation() throws Exception {
        // 住院行载荷（V1111 扩列形态）：m04OrderNo 在位即住院判别子，门诊收费链回流语义不适用
        new OutpatientDispenseCompletedListener(null, chargingService)
                .handleDispenseCompleted(envelope("{\"dispenseNo\":\"DP-1\",\"patientId\":\"9\","
                        + "\"visitId\":\"I2026100500001\",\"dispenseType\":\"INPATIENT\",\"lines\":[],"
                        + "\"m04OrderNo\":\"M04-001\",\"wardId\":\"W01\",\"dispensePlanNo\":\"DPN-1\"}"));

        // 跳过=不触达收费链委托（方法直返即 ack，住院计费归 M13 InpatientChargeService 路径）
        verifyNoInteractions(chargingService);
    }

    @Test
    @DisplayName("门诊行（rxNo 在位）照常委托收费链——分流不误伤既有路径")
    void outpatientRowStillRoutesToChargingDelegation() throws Exception {
        new OutpatientDispenseCompletedListener(null, chargingService)
                .handleDispenseCompleted(envelope("{\"dispenseNo\":\"DF20261005000001\","
                        + "\"rxNo\":\"RX20261005000001\",\"visitId\":\"O20261005000001\","
                        + "\"dispenseType\":\"WINDOW\",\"m04OrderNo\":null,\"lines\":[]}"));

        verify(chargingService).onDispenseCompleted("RX20261005000001", "DF20261005000001");
    }
}

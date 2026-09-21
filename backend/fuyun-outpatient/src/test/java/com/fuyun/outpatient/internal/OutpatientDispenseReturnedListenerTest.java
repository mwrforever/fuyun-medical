package com.fuyun.outpatient.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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
 * 退药受理回流监听器单测（pharmacy.dispense.returned 消费侧，Task 10）：编排所消费组件
 * （rxNo/fullReturn/dispenseNo）解析委托断言（lines 组件归 billing 占用回退面不消费不解析）。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（rxNo 锚守卫归 IChargingService）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientDispenseReturnedListenerTest {

    @Mock
    private IChargingService chargingService;

    /** 信封替身（按线格式 JSON 构造；Long 以 string 承载与 JacksonLongToStringConfig 同源） */
    private EventEnvelope envelope(String payloadJson) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "pharmacy",
                OutpatientMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_RETURNED,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("合规回流：解析 rxNo/fullReturn/dispenseNo 并委托 onDispenseReturned（整单退药 true 逐字）")
    void handleDelegatesConsumedFieldsToService() throws Exception {
        new OutpatientDispenseReturnedListener(null, chargingService)
                .handleDispenseReturned(envelope("{\"dispenseNo\":\"DF20260920001\",\"rxNo\":\"RX20260920000001\","
                        + "\"patientId\":\"9\",\"visitId\":\"O20260921000001\",\"fullReturn\":true,\"lines\":[]}"));

        verify(chargingService).onDispenseReturned(eq("RX20260920000001"), eq(true), eq("DF20260920001"));
    }

    @Test
    @DisplayName("回流缺 rxNo：解析委托不拦截（fullReturn 缺省 false、由业务侧锚守卫统一可读拒绝）")
    void handleDelegatesMalformedAnchorsToServiceGuard() throws Exception {
        new OutpatientDispenseReturnedListener(null, chargingService)
                .handleDispenseReturned(envelope("{\"dispenseNo\":\"DF20260920001\"}"));

        verify(chargingService).onDispenseReturned(any(), eq(false), any());
    }
}

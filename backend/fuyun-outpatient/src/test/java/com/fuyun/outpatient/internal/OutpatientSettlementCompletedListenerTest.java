package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 结算完成监听器单测（billing.settlement.completed 消费侧，Task 10）：合规十组件载荷解析委托
 * （组件逐字断言）。业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（模板三段式
 * 已在 IT 面验证；锚守卫/0 元拒绝归 IChargingService，BillingPharmacyOccupyListenerTest 同款形态）。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientSettlementCompletedListenerTest {

    @Mock
    private IChargingService chargingService;

    /** 信封替身（按线格式 JSON 构造；Long 以 string 承载与 JacksonLongToStringConfig 同源） */
    private EventEnvelope envelope(String payloadJson) throws Exception {
        return new EventEnvelope(
                "it-ev-1",
                Instant.now(Clock.systemUTC()),
                "billing",
                OutpatientMessagingConstants.EVENT_SUB_BILLING_SETTLEMENT_COMPLETED,
                "1",
                "it-trace",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("合规回执：解析十组件并委托 onSettlementCompleted（组件逐字断言）")
    void handleDelegatesWellFormedReceiptToService() throws Exception {
        new OutpatientSettlementCompletedListener(null, chargingService)
                .handleSettlementCompleted(envelope("{\"settlementId\":\"501\",\"settleNo\":\"S20260920001\","
                        + "\"patientId\":\"9\",\"visitId\":\"O20260921000001\",\"settleType\":\"OUT\","
                        + "\"payerType\":\"SELF_PAY\",\"totalAmount\":\"12000\",\"pooledAmount\":\"0\","
                        + "\"acctPayAmount\":\"0\",\"selfPayAmount\":\"12000\"}"));

        ArgumentCaptor<SettlementCompletedPayload> captor = ArgumentCaptor.forClass(SettlementCompletedPayload.class);
        verify(chargingService).onSettlementCompleted(captor.capture());
        SettlementCompletedPayload payload = captor.getValue();
        assertThat(payload.settlementId()).isEqualTo(501L);
        assertThat(payload.settleNo()).isEqualTo("S20260920001");
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.visitId()).isEqualTo("O20260921000001");
        assertThat(payload.settleType()).isEqualTo("OUT");
        assertThat(payload.payerType()).isEqualTo("SELF_PAY");
        assertThat(payload.totalAmount()).isEqualTo(12000L);
        assertThat(payload.selfPayAmount()).isEqualTo(12000L);
    }

    @Test
    @DisplayName("载荷缺锚字段：解析委托不拦截（缺失落 0/空由业务侧锚守卫统一可读拒绝）")
    void handleDelegatesMalformedAnchorsToServiceGuard() throws Exception {
        new OutpatientSettlementCompletedListener(null, chargingService)
                .handleSettlementCompleted(envelope("{\"settleType\":\"OUT\"}"));

        verify(chargingService).onSettlementCompleted(any());
    }
}

package com.fuyun.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.HttpStatus;

/** 开单事件消费单测：逐行生成命令映射（触发型分流）、BILL-1009 幂等吞、其余异常上抛走死信。 */
class BillingChargeEventListenerTest {

    private IdempotentConsumerSupport consumerSupport;
    private IPricingEngineService engine;
    private BillingChargeEventListener listener;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumerSupport = mock(IdempotentConsumerSupport.class);
        engine = mock(IPricingEngineService.class);
        listener = new BillingChargeEventListener(consumerSupport, engine);
    }

    /** 打桩模板：把交付 handler 取出直接以给定信封执行（绕开三段式，只测业务派发）。 */
    @SuppressWarnings("unchecked")
    private void driveHandler(EventEnvelope envelope) {
        doAnswer(inv -> {
                    ((Consumer<EventEnvelope>) inv.getArgument(1)).accept(envelope);
                    return null;
                })
                .when(consumerSupport)
                .consume(any(Message.class), any(Consumer.class));
    }

    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new EventEnvelope(
                "e-1", Instant.now(), "outpatient", eventType, "1", "t-1", mapper.readTree(payloadJson));
    }

    private Message raw() {
        return new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    @DisplayName("门诊开单事件：两行逐条生成，ORDER_LINKED+ORDER_CONFIRMED、sourceRef=orderId")
    void orderEventGeneratesOneFeePerLine() throws Exception {
        driveHandler(
                envelope(
                        "outpatient.order.created",
                        "{\"orderId\":\"ORD-9\",\"patientId\":7,\"visitId\":\"O2026091700001\","
                                + "\"lines\":[{\"itemCode\":\"C001\",\"quantity\":2},{\"itemCode\":\"C002\",\"quantity\":1}]}"));
        when(engine.generateFromSource(any())).thenReturn(1L);

        listener.onOutpatientOrderCreated(raw());

        ArgumentCaptor<FeeGenerateCommand> captor = ArgumentCaptor.forClass(FeeGenerateCommand.class);
        verify(engine, org.mockito.Mockito.times(2)).generateFromSource(captor.capture());
        FeeGenerateCommand first = captor.getAllValues().get(0);
        assertThat(first.source()).isEqualTo(ChargeSource.ORDER_LINKED);
        assertThat(first.trigger()).isEqualTo(TriggerType.ORDER_CONFIRMED);
        assertThat(first.sourceRef()).isEqualTo("ORD-9");
        assertThat(first.quantity()).isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    @DisplayName("处方生效事件：药品计费行触发型 PRESCRIPTION_EFFECTIVE（M-4 裁决口径）")
    void prescriptionEventUsesPrescriptionEffectiveTrigger() throws Exception {
        driveHandler(envelope(
                "pharmacy.prescription.created",
                "{\"prescriptionId\":\"RX-3\",\"patientId\":7,\"visitId\":\"O2026091700001\","
                        + "\"lines\":[{\"itemCode\":\"D001\",\"quantity\":1}]}"));
        when(engine.generateFromSource(any())).thenReturn(1L);

        listener.onPrescriptionCreated(raw());

        ArgumentCaptor<FeeGenerateCommand> captor = ArgumentCaptor.forClass(FeeGenerateCommand.class);
        verify(engine).generateFromSource(captor.capture());
        assertThat(captor.getValue().trigger()).isEqualTo(TriggerType.PRESCRIPTION_EFFECTIVE);
        assertThat(captor.getValue().sourceRef()).isEqualTo("RX-3");
    }

    @Test
    @DisplayName("BILL-1009 重复计费=幂等达成：吞过不重试；BILL-1001 缺项上抛走死信留痕")
    void duplicateSwallowedButMissingItemRethrows() throws Exception {
        driveHandler(envelope(
                "outpatient.order.created",
                "{\"orderId\":\"ORD-9\",\"patientId\":7,\"visitId\":\"O2026091700001\","
                        + "\"lines\":[{\"itemCode\":\"C001\",\"quantity\":1}]}"));
        when(engine.generateFromSource(any()))
                .thenThrow(new BizException(BillingErrorCode.DUPLICATE_CHARGING, HttpStatus.CONFLICT, "dup"))
                .thenThrow(new BizException(BillingErrorCode.CHARGE_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND, "missing"));

        listener.onOutpatientOrderCreated(raw()); // 第一帧：吞 BILL-1009 正常返回
        assertThatThrownBy(() -> listener.onOutpatientOrderCreated(raw()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.CHARGE_ITEM_NOT_FOUND));
    }

    @Test
    @DisplayName("payload 缺三要素/lines 空=不合规帧，抛异常交容器拒收进 fy.dlx（禁静默消费）")
    void malformedPayloadThrows() throws Exception {
        driveHandler(envelope("outpatient.order.created", "{\"orderId\":\"ORD-9\"}"));

        assertThatThrownBy(() -> listener.onOutpatientOrderCreated(raw())).isInstanceOf(IllegalStateException.class);
    }
}

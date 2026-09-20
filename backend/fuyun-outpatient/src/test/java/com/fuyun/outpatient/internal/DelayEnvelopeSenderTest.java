package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 延迟信封发送器单测（Task 5）：锚定 fy.delay 交换机 + delay.appointment-timeout 路由键 + 信封
 * eventType=outpatient.appointment.timeout（V204 id 39 字面量三方一致锚）+ 载荷三组件透传。
 */
@ExtendWith(MockitoExtension.class)
class DelayEnvelopeSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private DelayEnvelopeSender sender;

    @BeforeEach
    void setUp() {
        sender = new DelayEnvelopeSender(rabbitTemplate, new EventEnvelopeCodec(new ObjectMapper()));
    }

    @Test
    @DisplayName("send：信封按 fy.delay/delay.appointment-timeout 入队，eventType 与载荷三组件透传")
    void sendDelaysEnvelopeOnAppointmentTimeoutRoutingKey() {
        sender.send(new AppointmentTimeoutPayload("AP20260921000001", 9L, 31L));

        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("fy.delay"),
                        eq("delay.appointment-timeout"),
                        envelopeCaptor.capture(),
                        any(CorrelationData.class));
        EventEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.producer()).isEqualTo(OutpatientMessagingConstants.MODULE);
        assertThat(envelope.eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT);
        assertThat(envelope.payload().path("apptNo").asText()).isEqualTo("AP20260921000001");
        assertThat(envelope.payload().path("patientId").asLong()).isEqualTo(9L);
        assertThat(envelope.payload().path("poolId").asLong()).isEqualTo(31L);
    }
}

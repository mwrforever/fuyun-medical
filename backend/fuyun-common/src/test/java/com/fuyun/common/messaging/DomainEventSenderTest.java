package com.fuyun.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 发布模板单测（终审 Minor「发布器范式收敛」验收面）：信封七字段装配 + fy.topic 直发 +
 * CorrelationData 携 eventId；红线 = 构造零回调注册（共享单槽位归 SystemEventPublisher）。
 */
@ExtendWith(MockitoExtension.class)
class DomainEventSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("send 组信封直发 fy.topic：路由键=事件类型，CorrelationData=eventId")
    void sendBuildsEnvelopeAndPublishsToFyTopic() {
        EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());
        DomainEventSender sender = new DomainEventSender(rabbitTemplate, codec, "billing");

        sender.send("billing.fee.created", java.util.Map.of("feeId", 1L), "trace-1");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<CorrelationData> corr = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate)
                .convertAndSend(eq("fy.topic"), eq("billing.fee.created"), body.capture(), corr.capture());
        EventEnvelope envelope = (EventEnvelope) body.getValue();
        assertThat(envelope.producer()).isEqualTo("billing");
        assertThat(envelope.eventType()).isEqualTo("billing.fee.created");
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.payloadVersion()).isEqualTo("1");
        assertThat(corr.getValue().getId()).isEqualTo(envelope.eventId());
    }

    @Test
    @DisplayName("构造不注册 Confirm/Returns 回调（共享单槽位契约测试，IotEventPublisher 同款）")
    void constructorRegistersNoCallbacks() {
        EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());
        new DomainEventSender(rabbitTemplate, codec, "billing");

        verify(rabbitTemplate, org.mockito.Mockito.never())
                .setConfirmCallback(any(RabbitTemplate.ConfirmCallback.class));
        verify(rabbitTemplate, org.mockito.Mockito.never())
                .setReturnsCallback(any(RabbitTemplate.ReturnsCallback.class));
    }
}

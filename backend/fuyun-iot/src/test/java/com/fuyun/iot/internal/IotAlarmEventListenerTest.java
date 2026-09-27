package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 告警触发事件消费者单测（P2 PR-2 Task 9）：IdempotentConsumerSupport 标准三段式接入与联动
 * 执行器分派——重复投递跳过（D-7 回查）、成功消费登记 received_event（consumerModule=iot）后
 * 委托执行器、载荷契约不符按消费失败处置（settleFailure 失败收尾后重抛走死信）、执行器业务
 * 失败原样上抛（交容器有界重试）。
 */
@ExtendWith(MockitoExtension.class)
class IotAlarmEventListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "b4e1d3f5-6c70-4a82-8c93-1e4d5f6a7b90";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T07:00:00Z");

    /** 测试载荷：输液告急告警触发（联动预置模板匹配场景） */
    private static final AlarmTriggeredPayload PAYLOAD = new AlarmTriggeredPayload(
            "AL2026092600001",
            "it-dev-001",
            5L,
            "20260901000001",
            5L,
            "CRITICAL",
            "INFUSION_SHORTAGE",
            "80",
            77L,
            OCCURRED_AT);

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private LinkageExecutor executor;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    private IotAlarmEventListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = testObjectMapper();
        listener = new IotAlarmEventListener(
                new IdempotentConsumerSupport(
                        idempotencyService, new EventEnvelopeCodec(objectMapper), IotMessagingConstants.MODULE),
                executor,
                objectMapper);
    }

    @Test
    @DisplayName("重复投递跳过：tryAcquire 返回 false 直接返回（AUTO 确认），不触达执行器与登记")
    void skipsRedeliveredMessageWithoutExecutorOrRecord() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onAlarmTriggered(message(toJson(compliantEnvelope())));

        verifyNoInteractions(executor);
        verify(idempotencyService, never()).recordProcessed(any());
        verify(idempotencyService, never()).settleFailure(any(), any());
    }

    @Test
    @DisplayName("成功消费：解析载荷委托联动执行器，并按信封五要素登记 PROCESSED（consumerModule=iot）")
    void consumesEnvelopeAndDispatchesExecutor() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onAlarmTriggered(message(toJson(compliantEnvelope())));

        verify(executor).onAlarmTriggered(PAYLOAD);
        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        ReceivedEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo(EVENT_ID);
        assertThat(record.eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_TRIGGERED);
        assertThat(record.producer()).isEqualTo(IotMessagingConstants.MODULE);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.consumerModule()).isEqualTo(IotMessagingConstants.MODULE);
    }

    @Test
    @DisplayName("载荷契约不符：payload 与 AlarmTriggeredPayload 契约不符按消费失败处置（失败收尾后重抛走死信）")
    void settlesFailureAndRethrowsWhenPayloadViolatesContract() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);
        EventEnvelope violating = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                "1",
                null,
                objectMapper.valueToTree("scalar-payload"));

        assertThatThrownBy(() -> listener.onAlarmTriggered(message(toJson(violating))))
                .isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
        verify(idempotencyService, never()).recordProcessed(any());
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("执行器业务失败：settleFailure 失败收尾后原样重抛（交容器有界重试，不落 PROCESSED）")
    void settlesFailureAndRethrowsWhenExecutorFails() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);
        IllegalStateException failure = new IllegalStateException("联动执行失败");
        doThrow(failure).when(executor).onAlarmTriggered(any(AlarmTriggeredPayload.class));

        assertThatThrownBy(() -> listener.onAlarmTriggered(message(toJson(compliantEnvelope()))))
                .isSameAs(failure);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq(failure));
        verify(idempotencyService, never()).recordProcessed(any());
    }

    /** 构造合规信封：五要素齐全 + AlarmTriggeredPayload 契约载荷（codec 合规校验通过） */
    private EventEnvelope compliantEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                "1",
                null,
                objectMapper.valueToTree(PAYLOAD));
    }

    /** 信封序列化为线格式 JSON（与生产端 codec.toJson 同源） */
    private String toJson(EventEnvelope envelope) {
        return new EventEnvelopeCodec(objectMapper).toJson(envelope);
    }

    /** 构造原始消息帧（UTF-8 载体，与容器 SimpleMessageConverter 兜底形态一致） */
    private Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

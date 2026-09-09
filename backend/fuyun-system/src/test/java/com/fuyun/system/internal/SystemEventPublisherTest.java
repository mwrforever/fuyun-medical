package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.system.api.DictPublishedPayload;
import com.fuyun.system.constants.SystemMessagingConstants;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 系统模块 MQ 发布器单元测试（B3.2 发布侧，BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：构造期 Confirm/Returns 回调注册（A.5-4 发布确认姿态）；发布信封字段合规
 * （producer=system/eventType/traceId 透传/payload 契约 record）与路由三要素
 * （exchange/routing key/CorrelationData=eventId）；nack 与不可路由退回回调告警不抛出。
 * 真实 broker 的 AFTER_COMMIT 触发时机归 B3.3 端到端集成测试（DictBroadcastIT 规格）。
 */
@ExtendWith(MockitoExtension.class)
class SystemEventPublisherTest {

    /** 测试追踪锚点：MDC traceId（模拟 HTTP 线程发布点，TraceIdFilter 已建立上下文） */
    private static final String TRACE_ID = "it-publisher-trace";

    /** 固定时钟：信封 occurredAt 的确定性断言基准（测试资产，非真实凭证） */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Captor
    private ArgumentCaptor<Object> envelopeCaptor;

    @Captor
    private ArgumentCaptor<CorrelationData> correlationDataCaptor;

    private SystemEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SystemEventPublisher(rabbitTemplate, new EventEnvelopeCodec(testObjectMapper()));
        org.slf4j.MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 模拟 TraceIdFilter 收尾清理，防线程复用串号影响后续用例
        org.slf4j.MDC.remove("traceId");
    }

    @Test
    @DisplayName("构造期注册发布确认回调：Confirm 与 Returns 回调均挂到共享 RabbitTemplate（A.5-4）")
    void constructorRegistersConfirmAndReturnsCallbacks() {
        verify(rabbitTemplate).setConfirmCallback(publisher);
        verify(rabbitTemplate).setReturnsCallback(publisher);
    }

    @Test
    @DisplayName("发布广播：信封字段合规（producer/eventType/traceId/payload 契约）且路由三要素正确")
    void publishSendsCompliantEnvelopeWithCorrelationData() {
        publisher.onDictVersionPublished(new DictVersionPublishedEvent("gender", 2));

        verify(rabbitTemplate)
                .convertAndSend(
                        eq(SystemMessagingConstants.TOPIC_EXCHANGE),
                        eq(SystemMessagingConstants.EVENT_DICT_PUBLISHED),
                        envelopeCaptor.capture(),
                        correlationDataCaptor.capture());
        EventEnvelope envelope = (EventEnvelope) envelopeCaptor.getValue();
        assertThat(envelope.producer()).isEqualTo(SystemMessagingConstants.MODULE);
        assertThat(envelope.eventType()).isEqualTo(SystemMessagingConstants.EVENT_DICT_PUBLISHED);
        assertThat(envelope.payloadVersion()).isEqualTo("1");
        // traceId 透传：HTTP 线程发布点取 MDC 当前值（MQ 回调线程无上下文，故在发布点固定）
        assertThat(envelope.traceId()).isEqualTo(TRACE_ID);
        // payload 契约：DictPublishedPayload 字段映射（信封不感知具体业务载荷类型）
        assertThat(envelope.payload().path("dictType").asText()).isEqualTo("gender");
        assertThat(envelope.payload().path("version").asInt()).isEqualTo(2);
        // CorrelationData 携带 eventId：确认回调据此定位失败帧
        assertThat(correlationDataCaptor.getValue().getId()).isEqualTo(envelope.eventId());
    }

    @Test
    @DisplayName("broker nack：confirm 回调 error 告警且不抛出（P0 不自动重发，交人工排查）")
    void confirmCallbackLogsNackWithoutThrowing() {
        assertThatCode(() -> publisher.confirm(new CorrelationData("event-1"), false, "broker 内部故障"))
                .doesNotThrowAnyException();
        // ack=true 静默返回（防高频刷屏），不产生任何副作用
        assertThatCode(() -> publisher.confirm(new CorrelationData("event-2"), true, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不可路由退回：returns 回调 error 告警且不抛出，携带路由三要素")
    void returnsCallbackLogsUnroutableMessageWithoutThrowing() throws Exception {
        EventEnvelope envelope = new EventEnvelopeCodec(testObjectMapper())
                .create(
                        FIXED_CLOCK,
                        "system",
                        SystemMessagingConstants.EVENT_DICT_PUBLISHED,
                        TRACE_ID,
                        new DictPublishedPayload("gender", 1));
        Message message = new Message(testObjectMapper().writeValueAsBytes(envelope), new MessageProperties());

        assertThatCode(() -> publisher.returnedMessage(new ReturnedMessage(
                        message,
                        312,
                        "NO_ROUTE",
                        SystemMessagingConstants.TOPIC_EXCHANGE,
                        SystemMessagingConstants.EVENT_DICT_PUBLISHED)))
                .doesNotThrowAnyException();
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static com.fasterxml.jackson.databind.ObjectMapper testObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

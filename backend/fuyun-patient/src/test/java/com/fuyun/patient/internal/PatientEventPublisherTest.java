package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.patient.api.PatientUpdatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 患者域 MQ 发布器单元测试（Task 13，SystemEventPublisher 同款范式）。
 *
 * <p>覆盖：AFTER_COMMIT 中继语义（注解元数据反射断言 + 方法体直调）；发布信封与路由三要素
 * （exchange=fy.topic/routingKey=eventType/CorrelationData=eventId）；nack 与不可路由退回
 * 回调告警不抛出（含 correlationData 缺失与原文超长截断边界）。真实 broker 触发时机归后续
 * 端到端集成测试。
 */
@ExtendWith(MockitoExtension.class)
class PatientEventPublisherTest {

    /** 测试追踪锚点：MDC traceId（模拟 HTTP 线程发布点，TraceIdFilter 已建立上下文） */
    private static final String TRACE_ID = "task13-publisher-trace";

    /** 退回帧截断边界的超长原文（> 200 字符，覆盖 substring 截断分支） */
    private static final String LONG_BODY = "信".repeat(260);

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private EventEnvelopeCodec codec;

    @Captor
    private ArgumentCaptor<Object> envelopeCaptor;

    @Captor
    private ArgumentCaptor<CorrelationData> correlationDataCaptor;

    private PatientEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PatientEventPublisher(rabbitTemplate, codec);
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 模拟 TraceIdFilter 收尾清理，防线程复用串号影响后续用例
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("中继语义：监听方法必须挂 AFTER_COMMIT 事务事件监听（D-8 兜底 fallbackExecution=true）")
    void listenerAnnotationDeclaresAfterCommitWithFallback() throws Exception {
        Method listener = PatientEventPublisher.class.getMethod("onPatientDomainEvent", PatientDomainEvent.class);
        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        // D-8 裁决：无活动事务的发布点（补挂端点 controller 编排）也须出 MQ，禁静默丢失
        assertThat(annotation.fallbackExecution()).isTrue();
    }

    @Test
    @DisplayName("构造期注册发布确认回调：Confirm 与 Returns 回调均挂到共享 RabbitTemplate（A.5-4）")
    void constructorRegistersConfirmAndReturnsCallbacks() {
        verify(rabbitTemplate).setConfirmCallback(publisher);
        verify(rabbitTemplate).setReturnsCallback(publisher);
    }

    @Test
    @DisplayName("发布事件：信封按 producer=patient/eventType/traceId 生成，路由三要素与 CorrelationData=eventId 正确")
    void publishSendsEnvelopeWithRoutingTripleAndCorrelationData() {
        PatientUpdatedPayload payload = new PatientUpdatedPayload(42L, List.of("mobile"));
        EventEnvelope fixed = new EventEnvelope(
                "6a1c2b3d-4e5f-4a60-8b71-9c2b3d4e5f60",
                Instant.parse("2026-09-16T02:00:00Z"),
                PatientMessagingConstants.MODULE,
                PatientMessagingConstants.EVENT_UPDATED,
                "1",
                TRACE_ID,
                JsonNodeFactory.instance.objectNode());
        when(codec.create(
                        any(Clock.class),
                        eq(PatientMessagingConstants.MODULE),
                        eq(PatientMessagingConstants.EVENT_UPDATED),
                        eq(TRACE_ID),
                        eq(payload)))
                .thenReturn(fixed);

        publisher.onPatientDomainEvent(new PatientDomainEvent(PatientMessagingConstants.EVENT_UPDATED, payload));

        verify(rabbitTemplate)
                .convertAndSend(
                        eq(PatientMessagingConstants.TOPIC_EXCHANGE),
                        eq(PatientMessagingConstants.EVENT_UPDATED),
                        envelopeCaptor.capture(),
                        correlationDataCaptor.capture());
        assertThat(envelopeCaptor.getValue()).isSameAs(fixed);
        // CorrelationData 携带 eventId：确认回调据此定位失败帧（告警留痕，不自动重发）
        assertThat(correlationDataCaptor.getValue().getId()).isEqualTo(fixed.eventId());
    }

    @Test
    @DisplayName("broker nack：confirm 回调 error 告警且不抛出（correlationData 缺失与 ack=true 静默均为边界）")
    void confirmCallbackLogsNackWithoutThrowing() {
        assertThatCode(() -> publisher.confirm(new CorrelationData("event-1"), false, "broker 内部故障"))
                .doesNotThrowAnyException();
        // 极端场景 broker 未回带关联数据：eventId 记空不抛
        assertThatCode(() -> publisher.confirm(null, false, "无关联数据")).doesNotThrowAnyException();
        // ack=true 静默返回（防高频刷屏），不产生任何副作用
        assertThatCode(() -> publisher.confirm(new CorrelationData("event-2"), true, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不可路由退回：returns 回调 error 告警且不抛出，超长原文截断与空报文均不越界")
    void returnsCallbackLogsUnroutableMessageWithoutThrowing() throws Exception {
        Message longBodyMessage = new Message(LONG_BODY.getBytes(StandardCharsets.UTF_8), new MessageProperties());
        assertThatCode(() -> publisher.returnedMessage(new ReturnedMessage(
                        longBodyMessage,
                        312,
                        "NO_ROUTE",
                        PatientMessagingConstants.TOPIC_EXCHANGE,
                        PatientMessagingConstants.EVENT_UPDATED)))
                .doesNotThrowAnyException();
        // 空报文体的极端退回帧：空串兜底不越界
        Message emptyBodyMessage = new Message(new byte[0], new MessageProperties());
        assertThatCode(() -> publisher.returnedMessage(new ReturnedMessage(
                        emptyBodyMessage,
                        312,
                        "NO_ROUTE",
                        PatientMessagingConstants.TOPIC_EXCHANGE,
                        PatientMessagingConstants.EVENT_UPDATED)))
                .doesNotThrowAnyException();
    }
}

package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.patient.api.PatientUpdatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.lang.reflect.Method;
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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 患者域 MQ 发布器单元测试（Task 13 交付，Task 14 随回调归属修订同步）。
 *
 * <p>覆盖：AFTER_COMMIT 中继语义（注解元数据反射断言 + 方法体直调）；发布信封与路由三要素
 * （exchange=fy.topic/routingKey=eventType/CorrelationData=eventId）；回调归属契约——共享
 * RabbitTemplate 的单一 Confirm/Returns 槽位由 SystemEventPublisher 统一持有，本发布器不注册
 * （真栈多发布器共存前提，IotEventPublisher 同款断言）。真实 broker 触发时机归后续端到端集成测试。
 */
@ExtendWith(MockitoExtension.class)
class PatientEventPublisherTest {

    /** 测试追踪锚点：MDC traceId（模拟 HTTP 线程发布点，TraceIdFilter 已建立上下文） */
    private static final String TRACE_ID = "task13-publisher-trace";

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
    @DisplayName("回调归属：构造期不得注册 Confirm/Returns 回调（单槽位归 SystemEventPublisher，重复注册即启动失败）")
    void constructorDoesNotRegisterConfirmOrReturnsCallbacks() {
        // Spring AMQP 单槽位断言（Task 14 真栈冒烟实证）：第二个发布器注册即 IllegalStateException，
        // 本发布器复用 SystemEventPublisher 持有的共享回调告警通道（IotEventPublisher 同款契约）
        verifyNoInteractions(rabbitTemplate);
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
        // CorrelationData 携带 eventId：共享确认回调据此定位失败帧（告警留痕，不自动重发）
        assertThat(correlationDataCaptor.getValue().getId()).isEqualTo(fixed.eventId());
    }
}

package com.fuyun.iot.internal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IoT 域事件发布器单元测试（CF-7 发布装配，InpatientEventPublisher 范式同构）：断言监听方法对
 * sender 的三要素透传——eventType/payload 按事件原样、traceId 透传事件携带值（发布点捕获，
 * AMQP 线程发布点为 null 时原样透传不取上下文）。真实事务时机（AFTER_COMMIT/fallbackExecution）
 * 由 Spring 事务事件机制注解承载，真栈全链路归 fuyun-app MessagingGovernanceIT 验证。
 */
@ExtendWith(MockitoExtension.class)
class IotDomainPublisherTest {

    /** 测试追踪锚点：发布点捕获的 traceId（模拟有日志上下文的发布线程） */
    private static final String TRACE_ID = "it-iot-domain-publisher-trace";

    @Mock
    private DomainEventSender sender;

    @Test
    @DisplayName("透传三要素：eventType/payload 按事件原样，traceId 透传发布点捕获值")
    void forwardsEventTypePayloadAndCapturedTraceIdToSender() {
        IotDomainPublisher publisher = new IotDomainPublisher(sender);
        AlarmTriggeredPayload payload = new AlarmTriggeredPayload(
                "AL20260926001",
                "it-dev-001",
                1001L,
                "V2026090001",
                3L,
                "WARNING",
                "heart_rate",
                "128",
                7L,
                Instant.parse("2026-09-26T02:30:00Z"));
        IotDomainEvent event = new IotDomainEvent(
                IotMessagingConstants.EVENT_ALARM_TRIGGERED, payload, Instant.parse("2026-09-26T02:30:00Z"), TRACE_ID);

        publisher.onIotDomainEvent(event);

        verify(sender).send(IotMessagingConstants.EVENT_ALARM_TRIGGERED, payload, TRACE_ID);
    }

    @Test
    @DisplayName("MQ/异步线程发布点无日志上下文：traceId 以 null 原样透传，不额外取 MDC")
    void forwardsNullTraceIdWhenPublishPointHasNoContext() {
        IotDomainPublisher publisher = new IotDomainPublisher(sender);
        TelemetryAnomalyPayload payload = new TelemetryAnomalyPayload(
                "it-dev-002",
                "spo2",
                "STALLED",
                Instant.parse("2026-09-26T02:00:00Z"),
                Instant.parse("2026-09-26T02:05:00Z"));
        IotDomainEvent event =
                new IotDomainEvent(IotMessagingConstants.EVENT_TELEMETRY_ANOMALY, payload, payload.detectedAt(), null);

        publisher.onIotDomainEvent(event);

        verify(sender).send(eq(IotMessagingConstants.EVENT_TELEMETRY_ANOMALY), eq(payload), isNull());
    }
}

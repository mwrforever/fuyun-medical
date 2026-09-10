package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.enums.DeviceStatus;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * IoT 设备状态事件发布器单元测试（B4.3 扇出侧，BRIEF-PR4-01 §1.5，SystemEventPublisherTest 同构）。
 *
 * <p>覆盖：共享模板回调不抢占（Spring AMQP 单一 Confirm/Returns 回调槽位由 SystemEventPublisher
 * 统一持有，偏差申报见 IotEventPublisher 类注释）；发布信封字段合规（producer=iot/eventType/
 * traceId 透传/payload=DeviceStatusEvent 契约）与路由三要素（exchange/routing key/
 * CorrelationData=eventId）。真实 broker 全链归 IotTelemetryPipelineIT 步骤 5/6（状态扇出与
 * 幂等重投），nack/不可路由告警经共享回调承载（SystemEventPublisherTest 已覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class IotEventPublisherTest {

    /** 测试追踪锚点：MDC traceId（模拟调用线程已有 TraceIdFilter 上下文） */
    private static final String TRACE_ID = "it-iot-publisher-trace";

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Captor
    private ArgumentCaptor<Object> envelopeCaptor;

    @Captor
    private ArgumentCaptor<CorrelationData> correlationDataCaptor;

    private IotEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new IotEventPublisher(rabbitTemplate, new EventEnvelopeCodec(testObjectMapper()));
        org.slf4j.MDC.put(IotMessagingConstants.TRACE_ID_MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 模拟 TraceIdFilter 收尾清理，防线程复用串号影响后续用例
        org.slf4j.MDC.remove(IotMessagingConstants.TRACE_ID_MDC_KEY);
    }

    @Test
    @DisplayName("构造期不抢占共享模板回调：单一 Confirm/Returns 槽位由 SystemEventPublisher 统一持有")
    void constructorDoesNotHijackSharedTemplateCallbacks() {
        // Spring AMQP 断言仅支持单一回调（第二个不同实例启动失败）：本发布器注册即与 system 发布器互斥
        verify(rabbitTemplate, never()).setConfirmCallback(any());
        verify(rabbitTemplate, never()).setReturnsCallback(any());
    }

    @Test
    @DisplayName("发布状态事件：信封字段合规（producer/eventType/traceId/payload 契约）且路由三要素正确")
    void publishDeviceStatusSendsCompliantEnvelopeWithCorrelationData() {
        DeviceStatusEvent event =
                new DeviceStatusEvent("it-dev-001", DeviceStatus.OFFLINE, Instant.parse("2026-09-10T07:00:00Z"), null);

        publisher.publishDeviceStatus(event);

        verify(rabbitTemplate)
                .convertAndSend(
                        eq(IotMessagingConstants.TOPIC_EXCHANGE),
                        eq(IotMessagingConstants.EVENT_DEVICE_STATUS),
                        envelopeCaptor.capture(),
                        correlationDataCaptor.capture());
        EventEnvelope envelope = (EventEnvelope) envelopeCaptor.getValue();
        assertThat(envelope.producer()).isEqualTo(IotMessagingConstants.MODULE);
        assertThat(envelope.eventType()).isEqualTo(IotMessagingConstants.EVENT_DEVICE_STATUS);
        assertThat(envelope.payloadVersion()).isEqualTo("1");
        // traceId 透传：发布点取 MDC 当前值（AMQP 消费线程调用时无上下文为 null，契约允许）
        assertThat(envelope.traceId()).isEqualTo(TRACE_ID);
        // payload 契约：DeviceStatusEvent 字段映射（status 序列化为枚举 code，wardId 可空承载 null）
        assertThat(envelope.payload().path("deviceId").asText()).isEqualTo("it-dev-001");
        assertThat(envelope.payload().path("status").asText()).isEqualTo(DeviceStatus.OFFLINE.getCode());
        assertThat(envelope.payload().path("occurredAt").asText()).isEqualTo("2026-09-10T07:00:00Z");
        assertThat(envelope.payload().path("wardId").isNull()).isTrue();
        // CorrelationData 携带 eventId：共享确认回调据此定位失败帧
        assertThat(correlationDataCaptor.getValue().getId()).isEqualTo(envelope.eventId());
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static com.fasterxml.jackson.databind.ObjectMapper testObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

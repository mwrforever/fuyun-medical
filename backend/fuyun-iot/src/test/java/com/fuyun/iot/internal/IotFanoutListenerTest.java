package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.service.ITelemetryPushService;
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
 * 设备状态自事件消费者单元测试（标准幂等范式三分支与不合规信封处置，BRIEF-PR4-01 §1.5，
 * DictPublishedListenerTest 同构）。
 *
 * <p>覆盖：重复投递（tryAcquire=false，D-7 回查仅认 PROCESSED 行）跳过即 AUTO 确认、成功消费落
 * received_event 登记（信封五要素完整，consumerModule=iot）、业务失败 settleFailure 失败收尾
 * （释放前置键 + FAILED 留痕）后原样重抛（交容器有界重试）、不合规信封上抛（不触达幂等构件）、
 * 载荷契约不符按消费失败处置、B4.3-b 接线——载荷含 wardId 推 STOMP 设备状态主题、wardId null
 * 跳过推送、推送失败按业务失败收尾重抛。真实 broker 链路（含幂等重投与 STOMP 收帧）归
 * IotTelemetryPipelineIT 步骤 5/6。
 */
@ExtendWith(MockitoExtension.class)
class IotFanoutListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，测试样本固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "a3d0c2e4-5b6f-4a71-9b82-0d3c4e5f6a80";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-10T07:00:00Z");

    /** 测试载荷：设备离线事件（wardId 可空承载 null） */
    private static final DeviceStatusEvent STATUS_EVENT =
            new DeviceStatusEvent("it-dev-001", DeviceStatus.OFFLINE, OCCURRED_AT, null);

    /** 测试病区 ID：带 ward 主题推送断言值 */
    private static final long WARD_ID = 1001L;

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private ITelemetryPushService pushService;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    private IotFanoutListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = testObjectMapper();
        listener = new IotFanoutListener(
                idempotencyService, new EventEnvelopeCodec(objectMapper), objectMapper, pushService);
    }

    @Test
    @DisplayName("重复投递跳过：tryAcquire 返回 false 直接返回（AUTO 确认），不触达业务与登记")
    void skipsRedeliveredMessageWithoutBusinessOrRecord() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onDeviceStatusChanged(message(toJson(compliantEnvelope())));

        verify(idempotencyService, never()).recordProcessed(any());
        verify(idempotencyService, never()).settleFailure(any(), any());
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("成功消费：解析载荷留痕后按信封五要素登记 PROCESSED（consumerModule=iot）")
    void consumesEnvelopeAndRecordsProcessedWithEnvelopeIdentity() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onDeviceStatusChanged(message(toJson(compliantEnvelope())));

        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        ReceivedEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo(EVENT_ID);
        assertThat(record.eventType()).isEqualTo(IotMessagingConstants.EVENT_DEVICE_STATUS);
        assertThat(record.producer()).isEqualTo(IotMessagingConstants.MODULE);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.consumerModule()).isEqualTo(IotMessagingConstants.MODULE);
        // 载荷 wardId 可空（契约允许：解析产物恒 null，档案补全由发布侧完成）：推送委托照常发生，
        // 跳过决策在推送服务内（info 降级）
        verify(pushService).pushDeviceStatus(STATUS_EVENT);
    }

    @Test
    @DisplayName("B4.3-b 接线：载荷含 wardId 消费后推送设备状态主题（载荷与事件契约同构）")
    void pushesDeviceStatusTopicWhenPayloadCarriesWardId() {
        DeviceStatusEvent wardEvent = new DeviceStatusEvent("it-dev-001", DeviceStatus.OFFLINE, OCCURRED_AT, WARD_ID);
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);
        EventEnvelope envelope = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                "1",
                null,
                objectMapper.valueToTree(wardEvent));

        listener.onDeviceStatusChanged(message(toJson(envelope)));

        verify(pushService).pushDeviceStatus(wardEvent);
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("推送失败按业务失败处置：settleFailure 失败收尾后重抛（交容器有界重试，不落 PROCESSED）")
    void settlesFailureAndRethrowsWhenPushFails() {
        DeviceStatusEvent wardEvent = new DeviceStatusEvent("it-dev-001", DeviceStatus.OFFLINE, OCCURRED_AT, WARD_ID);
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);
        IllegalStateException failure = new IllegalStateException("STOMP 推送失败");
        doThrow(failure).when(pushService).pushDeviceStatus(wardEvent);
        EventEnvelope envelope = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                "1",
                null,
                objectMapper.valueToTree(wardEvent));

        assertThatThrownBy(() -> listener.onDeviceStatusChanged(message(toJson(envelope))))
                .isSameAs(failure);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq(failure));
        verify(idempotencyService, never()).recordProcessed(any());
    }

    @Test
    @DisplayName("业务失败收尾重抛：recordProcessed 异常时 settleFailure 承接并原样上抛（交有界重试）")
    void settlesFailureAndRethrowsOnBusinessFailure() {
        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);
        IllegalStateException failure = new IllegalStateException("登记失败");
        doThrow(failure).when(idempotencyService).recordProcessed(any());

        assertThatThrownBy(() -> listener.onDeviceStatusChanged(message(toJson(compliantEnvelope()))))
                .isSameAs(failure);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq(failure));
    }

    @Test
    @DisplayName("不合规信封拒收：codec 校验失败上抛 IllegalArgumentException，不触达幂等构件（转死信留痕）")
    void rejectsNonCompliantEnvelopeBeforeIdempotency() {
        // 缺 eventId 的信封帧：消费侧合规校验（M20 红线 1）拒绝
        String nonCompliant = "{\"producer\":\"iot\",\"eventType\":\"iot.device.status-changed\","
                + "\"payloadVersion\":\"1\",\"payload\":{\"deviceId\":\"it-dev-001\",\"status\":\"OFFLINE\","
                + "\"occurredAt\":\"2026-09-10T07:00:00Z\"}}";

        assertThatThrownBy(() -> listener.onDeviceStatusChanged(message(nonCompliant)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(idempotencyService, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    @DisplayName("载荷契约不符：payload 非契约对象按业务失败处置（失败收尾留痕后重抛走死信）")
    void settlesFailureAndRethrowsWhenPayloadViolatesContract() {
        // payload 为标量字符串：fromJson 合规（payload 非空）但与 DeviceStatusEvent 契约不符
        EventEnvelope violating = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                "1",
                null,
                objectMapper.valueToTree("scalar-payload"));

        when(idempotencyService.tryAcquire(EVENT_ID, IotMessagingConstants.MODULE))
                .thenReturn(true);

        // 捕获监听器抛出的业务异常：断言 settleFailure 以同一异常承接（W-6③ 主异常语义的衔接点）
        Throwable thrown = catchThrowable(() -> listener.onDeviceStatusChanged(message(toJson(violating))));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), eq((RuntimeException) thrown));
        verify(idempotencyService, never()).recordProcessed(any());
    }

    /** 构造合规信封：五要素齐全 + DeviceStatusEvent 契约载荷（codec 合规校验通过） */
    private EventEnvelope compliantEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                "1",
                null,
                objectMapper.valueToTree(STATUS_EVENT));
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
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

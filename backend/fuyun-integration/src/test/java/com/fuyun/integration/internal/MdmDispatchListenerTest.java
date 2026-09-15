package com.fuyun.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.MdmDispatchLog;
import com.fuyun.integration.mapper.MdmDispatchLogMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 主数据分发流水消费者单测：订阅方清单进 target_modules、version 只取治理字段、
 * 非主数据事件拒绝、失败路径走 settleFailure 后重抛（标准消费范式）。
 */
@ExtendWith(MockitoExtension.class)
class MdmDispatchListenerTest {

    /** 测试事件号：信封 eventId */
    private static final String EVENT_ID = "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60";

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private IMdmSubscriptionService subscriptionService;

    @Mock
    private MdmDispatchLogMapper dispatchLogMapper;

    private MdmDispatchListener listener;

    @BeforeEach
    void setUp() {
        EventEnvelopeCodec codec = new EventEnvelopeCodec(testObjectMapper());
        listener = new MdmDispatchListener(idempotencyService, codec, subscriptionService, dispatchLogMapper);
    }

    @Test
    @DisplayName("分发流水登记：topic 由事件类型推导，version 取载荷治理字段，target_modules 为订阅方清单")
    void recordsDispatchLogWithSubscriberTargets() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_DICT)).thenReturn(List.of("it", "lab"));

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 3)));

        ArgumentCaptor<MdmDispatchLog> captor = ArgumentCaptor.forClass(MdmDispatchLog.class);
        verify(dispatchLogMapper).insert(captor.capture());
        MdmDispatchLog row = captor.getValue();
        assertThat(row.getTopic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(row.getVersion()).isEqualTo(3L);
        assertThat(row.getDispatchMode()).isEqualTo(MdmConstants.DISPATCH_MODE_BROADCAST);
        assertThat(row.getTargetModules()).isEqualTo("it,lab");
        assertThat(row.getDispatchedAt())
                .isEqualTo(Instant.parse("2026-09-15T01:02:03Z").atOffset(java.time.ZoneOffset.UTC));
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("占位 schema 事件：载荷无 version 字段时登记 null（不解读其他业务字段）")
    void recordsNullVersionWhenPayloadHasNoVersionField() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_ORG)).thenReturn(List.of());

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_ORG_CHANGED, null)));

        ArgumentCaptor<MdmDispatchLog> captor = ArgumentCaptor.forClass(MdmDispatchLog.class);
        verify(dispatchLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isNull();
        assertThat(captor.getValue().getTargetModules()).isEmpty();
    }

    @Test
    @DisplayName("重复投递：tryAcquire 返回 false 时直接跳过，不写流水不登记")
    void skipsDuplicateDelivery() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(false);

        listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 1)));

        verify(dispatchLogMapper, never()).insert(any(MdmDispatchLog.class));
        verify(idempotencyService, never()).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("非主数据事件：拒绝消费（装配与契约漂移显性失败），失败路径经 settleFailure 后重抛")
    void rejectsNonMasterDataEventAndSettlesFailure() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);

        assertThatThrownBy(() -> listener.onMasterDataChanged(message(envelope("iot.telemetry.message", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主数据主题");
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
        verify(dispatchLogMapper, never()).insert(any(MdmDispatchLog.class));
    }

    @Test
    @DisplayName("落库失败：settleFailure 收尾后重抛交容器重试（不吞错）")
    void rethrowsWhenDispatchLogInsertFails() {
        when(idempotencyService.tryAcquire(EVENT_ID, MessagingConstants.MODULE)).thenReturn(true);
        when(subscriptionService.listSubscriberModules(MdmConstants.TOPIC_DICT)).thenReturn(List.of("it"));
        when(dispatchLogMapper.insert(any(MdmDispatchLog.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库连接不可用"));

        assertThatCode(() -> listener.onMasterDataChanged(message(envelope(MdmConstants.EVENT_DICT_PUBLISHED, 1))))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
    }

    /**
     * 构造合规信封：eventType 指定、version 可空（null 时不写入载荷 version 字段）。
     *
     * @param eventType 事件类型
     * @param version   载荷 version 字段值，可空
     * @return 事件信封
     */
    private EventEnvelope envelope(String eventType, Integer version) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (version != null) {
            payload.put("version", version);
        }
        return new EventEnvelope(
                EVENT_ID, Instant.parse("2026-09-15T01:02:03Z"), "system", eventType, "1", null, payload);
    }

    /**
     * 信封对象 → raw MQ 帧（消费侧以 HTTP 无关的原文承接）。
     *
     * @param envelope 事件信封
     * @return raw Message
     */
    private Message message(EventEnvelope envelope) {
        String body = new EventEnvelopeCodec(testObjectMapper()).toJson(envelope);
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

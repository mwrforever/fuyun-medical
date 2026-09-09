package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.system.api.DictPublishedPayload;
import com.fuyun.system.constants.SystemMessagingConstants;
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
 * 字典发布广播消费者单元测试（标准幂等范式三分支与不合规信封处置，BRIEF-PR3-01 §3.2）。
 *
 * <p>覆盖：重复投递（tryAcquire=false，D-7 回查确认已处理）跳过即 AUTO 确认、成功消费
 * 落 received_event 登记（信封五要素完整）、业务失败释放前置键后重抛（交容器有界重试）、
 * 不合规信封上抛（不触达幂等构件）、载荷契约不符按消费失败处置。真实 broker 链路归
 * B3.3 DictBroadcastIT。
 */
@ExtendWith(MockitoExtension.class)
class DictPublishedListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，测试样本固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "c2d0a3d4-5e6f-4a71-9b82-0d3c4e5f6a70";

    /** 测试追踪锚点 */
    private static final String TRACE_ID = "it-listener-trace";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-09T08:00:00Z");

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    private DictPublishedListener listener;

    @BeforeEach
    void setUp() {
        listener = new DictPublishedListener(
                idempotencyService, new EventEnvelopeCodec(testObjectMapper()), testObjectMapper());
    }

    @Test
    @DisplayName("重复投递跳过：tryAcquire 返回 false 直接返回（AUTO 确认），不触达业务与登记")
    void skipsRedeliveredMessageWithoutBusinessOrRecord() {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onDictPublished(message(toJson(compliantEnvelope())));

        verify(idempotencyService, never()).recordProcessed(any());
        verify(idempotencyService, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("成功消费：解析载荷留痕后按信封五要素登记 PROCESSED（consumerModule=system）")
    void consumesEnvelopeAndRecordsProcessedWithEnvelopeIdentity() {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onDictPublished(message(toJson(compliantEnvelope())));

        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        ReceivedEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo(EVENT_ID);
        assertThat(record.eventType()).isEqualTo(SystemMessagingConstants.EVENT_DICT_PUBLISHED);
        assertThat(record.producer()).isEqualTo(SystemMessagingConstants.MODULE);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.consumerModule()).isEqualTo(SystemMessagingConstants.MODULE);
    }

    @Test
    @DisplayName("业务失败释放重抛：recordProcessed 异常时释放前置键并原样上抛（交有界重试）")
    void releasesIdempotencyKeyAndRethrowsOnBusinessFailure() {
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);
        IllegalStateException failure = new IllegalStateException("登记失败");
        doThrow(failure).when(idempotencyService).recordProcessed(any());

        assertThatThrownBy(() -> listener.onDictPublished(message(toJson(compliantEnvelope()))))
                .isSameAs(failure);
        verify(idempotencyService).release(EVENT_ID, SystemMessagingConstants.MODULE);
    }

    @Test
    @DisplayName("不合规信封拒收：codec 校验失败上抛 IllegalArgumentException，不触达幂等构件（转死信留痕）")
    void rejectsNonCompliantEnvelopeBeforeIdempotency() {
        // 缺 eventId 的信封帧：消费侧合规校验（M20 红线 1）拒绝
        String nonCompliant = "{\"producer\":\"system\",\"eventType\":\"system.dict.published\","
                + "\"payloadVersion\":\"1\",\"payload\":{\"dictType\":\"gender\",\"version\":1}}";

        assertThatThrownBy(() -> listener.onDictPublished(message(nonCompliant)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(idempotencyService, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    @DisplayName("载荷契约不符：payload 非契约对象按业务失败处置（释放前置键后重抛走死信）")
    void releasesAndRethrowsWhenPayloadViolatesContract() {
        // payload 为标量字符串：fromJson 合规（payload 非空）但与 DictPublishedPayload 契约不符
        EventEnvelope violating = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_DICT_PUBLISHED,
                "1",
                TRACE_ID,
                testObjectMapper().valueToTree("not-a-contract-payload"));
        when(idempotencyService.tryAcquire(EVENT_ID, SystemMessagingConstants.MODULE))
                .thenReturn(true);

        assertThatThrownBy(() -> listener.onDictPublished(message(toJson(violating))))
                .isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).release(EVENT_ID, SystemMessagingConstants.MODULE);
        verify(idempotencyService, never()).recordProcessed(any());
    }

    /** 构造合规信封样本：eventId 固定为样本常量，载荷 = DictPublishedPayload 契约字段 */
    private EventEnvelope compliantEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_DICT_PUBLISHED,
                "1",
                TRACE_ID,
                testObjectMapper().valueToTree(new DictPublishedPayload("gender", 2)));
    }

    /** 信封序列化为线格式 JSON（与生产发布侧同构，序列化失败属测试资产缺陷直接抛出） */
    private String toJson(EventEnvelope envelope) {
        try {
            return testObjectMapper().writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试信封序列化失败", e);
        }
    }

    /** 构造 raw 消息帧（UTF-8 编码 JSON body，与生产发布侧 contentType 同构） */
    private Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    /** 测试用 ObjectMapper：注册 JavaTimeModule 并关闭时间戳形态（对齐 Boot 全局定制实例行为） */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

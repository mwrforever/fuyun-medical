package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 患者自事件缓存失效消费者单元测试（Task 13，MdmDispatchListener 同款标准幂等范式）。
 *
 * <p>覆盖：merged/split 双档案失效、frozen/updated/identifier.changed 单档案失效、未登记事件
 * 类型按失败处置（settleFailure 后重抛交容器重试）、重复投递跳过（幂等不触失效）。
 */
@ExtendWith(MockitoExtension.class)
class PatientCacheInvalidationListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态） */
    private static final String EVENT_ID = "c2d3e4f5-a6b7-4c80-9d21-3e4f5a6b7c80";

    /** 主档 id 与从档 id（merged/split 双失效断言用） */
    private static final long SURVIVOR_ID = 201L;

    private static final long MERGED_ID = 202L;

    private static final long RESTORED_ID = 203L;

    private static final long SINGLE_ID = 301L;

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private EventEnvelopeCodec codec;

    @Mock
    private PatientCacheService cacheService;

    private PatientCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        listener = new PatientCacheInvalidationListener(idempotencyService, codec, cacheService);
    }

    @Test
    @DisplayName("merged 事件：主从双档案 evictView 失效并登记 PROCESSED")
    void evictsBothArchivesOnMergedEvent() {
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(true);
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_MERGED,
                        payload().put("survivorPatientId", SURVIVOR_ID).put("mergedPatientId", MERGED_ID)));

        listener.onPatientEvent(message());

        verify(cacheService).evictView(SURVIVOR_ID);
        verify(cacheService).evictView(MERGED_ID);
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("split 事件：恢复档与原主档双 evictView 失效")
    void evictsRestoredAndSurvivorOnSplitEvent() {
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(true);
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_SPLIT,
                        payload().put("restoredPatientId", RESTORED_ID).put("survivorPatientId", SURVIVOR_ID)));

        listener.onPatientEvent(message());

        verify(cacheService).evictView(RESTORED_ID);
        verify(cacheService).evictView(SURVIVOR_ID);
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("frozen 事件：单档案 evictView 失效（updated/unfrozen/identifier.changed 同分支）")
    void evictsSingleArchiveOnFrozenEvent() {
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(true);
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_FROZEN, payload().put("patientId", SINGLE_ID)));

        listener.onPatientEvent(message());

        verify(cacheService).evictView(SINGLE_ID);
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("identifier.changed 事件：按 patientId 单档案失效（解析缓存一致性依据）")
    void evictsSingleArchiveOnIdentifierChangedEvent() {
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(true);
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED,
                        payload().put("patientId", SINGLE_ID)));

        listener.onPatientEvent(message());

        verify(cacheService).evictView(SINGLE_ID);
        verify(idempotencyService).recordProcessed(any(ReceivedEventRecord.class));
    }

    @Test
    @DisplayName("未登记的自消费事件类型：按失败处置——settleFailure 留痕后重抛，禁静默吞掉")
    void rejectsUnregisteredEventTypeWithSettleFailureAndRethrow() {
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(true);
        // 已登记但不属于本模块自消费全集的事件类型：队列绑定与常量漂移的显性失败
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED,
                        payload().put("patientId", SINGLE_ID)));

        assertThatThrownBy(() -> listener.onPatientEvent(message()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自消费事件类型");
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
        verify(idempotencyService, never()).recordProcessed(any(ReceivedEventRecord.class));
        verify(cacheService, never()).evictView(eq(SINGLE_ID));
    }

    @Test
    @DisplayName("重复投递：tryAcquire 返回 false 直接跳过，不触失效不登记（AUTO 确认）")
    void skipsDuplicateDeliveryWithoutEviction() {
        when(codec.fromJson(anyString()))
                .thenReturn(envelope(
                        PatientMessagingConstants.EVENT_UPDATED, payload().put("patientId", SINGLE_ID)));
        when(idempotencyService.tryAcquire(EVENT_ID, PatientMessagingConstants.MODULE))
                .thenReturn(false);

        assertThatCode(() -> listener.onPatientEvent(message())).doesNotThrowAnyException();
        verify(cacheService, never()).evictView(anyLong());
        verify(idempotencyService, never()).recordProcessed(any(ReceivedEventRecord.class));
        verify(idempotencyService, never()).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
    }

    /** 空 ObjectNode 载荷基底（各用例按事件契约补字段） */
    private static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * 构造合规信封：producer=patient（自事件回环）、traceId 置空（MQ 线程无日志上下文）。
     *
     * @param eventType 事件类型
     * @param payload   载荷节点
     * @return 事件信封
     */
    private static EventEnvelope envelope(String eventType, ObjectNode payload) {
        return new EventEnvelope(
                EVENT_ID,
                Instant.parse("2026-09-16T02:00:00Z"),
                PatientMessagingConstants.MODULE,
                eventType,
                "1",
                null,
                payload);
    }

    /**
     * 信封 → raw MQ 帧：以真实线格式序列化落报文体（消费侧以原文承接，__TypeId__ 头不作依据）。
     *
     * @return raw Message
     */
    private static Message message() {
        String body = "{\"eventId\":\"" + EVENT_ID + "\"}";
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}

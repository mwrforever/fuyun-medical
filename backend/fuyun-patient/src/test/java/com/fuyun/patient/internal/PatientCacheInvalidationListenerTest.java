package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.time.Instant;
import java.util.function.Consumer;
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
 * 患者自事件缓存失效消费者单元测试（Task 13 交付；Task 5 范式收敛改 verify 委托——幂等三段式
 * 「重复跳过/成功登记/失败留痕」断言下沉 common IdempotentConsumerSupportTest 承载）。
 *
 * <p>覆盖：raw 帧直通模板不自解析（委托契约）；merged/split 双档案失效、frozen/updated/
 * identifier.changed 单档案失效、未登记事件类型按失败处置（doEvict 抛出交模板 settleFailure）。
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
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private PatientCacheService cacheService;

    @Captor
    private ArgumentCaptor<Consumer<EventEnvelope>> handlerCaptor;

    private PatientCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        listener = new PatientCacheInvalidationListener(consumerSupport, cacheService);
    }

    @Test
    @DisplayName("委托契约：raw 消息帧直通模板 consume（监听器不自解析，AUTO 确认语义交容器）")
    void forwardsRawFrameToTemplateConsumer() {
        Message frame = message();

        listener.onPatientEvent(frame);

        verify(consumerSupport).consume(same(frame), any());
    }

    @Test
    @DisplayName("merged 事件：主从双档案 evictView 失效")
    void evictsBothArchivesOnMergedEvent() {
        listener.onPatientEvent(message());
        verify(consumerSupport).consume(any(Message.class), handlerCaptor.capture());

        handlerCaptor
                .getValue()
                .accept(envelope(
                        PatientMessagingConstants.EVENT_MERGED,
                        payload().put("survivorPatientId", SURVIVOR_ID).put("mergedPatientId", MERGED_ID)));

        verify(cacheService).evictView(SURVIVOR_ID);
        verify(cacheService).evictView(MERGED_ID);
    }

    @Test
    @DisplayName("split 事件：恢复档与原主档双 evictView 失效")
    void evictsRestoredAndSurvivorOnSplitEvent() {
        listener.onPatientEvent(message());
        verify(consumerSupport).consume(any(Message.class), handlerCaptor.capture());

        handlerCaptor
                .getValue()
                .accept(envelope(
                        PatientMessagingConstants.EVENT_SPLIT,
                        payload().put("restoredPatientId", RESTORED_ID).put("survivorPatientId", SURVIVOR_ID)));

        verify(cacheService).evictView(RESTORED_ID);
        verify(cacheService).evictView(SURVIVOR_ID);
    }

    @Test
    @DisplayName("frozen 事件：单档案 evictView 失效（updated/unfrozen/identifier.changed 同分支）")
    void evictsSingleArchiveOnFrozenEvent() {
        listener.onPatientEvent(message());
        verify(consumerSupport).consume(any(Message.class), handlerCaptor.capture());

        handlerCaptor
                .getValue()
                .accept(envelope(
                        PatientMessagingConstants.EVENT_FROZEN, payload().put("patientId", SINGLE_ID)));

        verify(cacheService).evictView(SINGLE_ID);
    }

    @Test
    @DisplayName("identifier.changed 事件：按 patientId 单档案失效（解析缓存一致性依据）")
    void evictsSingleArchiveOnIdentifierChangedEvent() {
        listener.onPatientEvent(message());
        verify(consumerSupport).consume(any(Message.class), handlerCaptor.capture());

        handlerCaptor
                .getValue()
                .accept(envelope(
                        PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED,
                        payload().put("patientId", SINGLE_ID)));

        verify(cacheService).evictView(SINGLE_ID);
    }

    @Test
    @DisplayName("未登记的自消费事件类型：失效派发按失败抛出（交模板 settleFailure 留痕，禁静默吞掉）")
    void rejectsUnregisteredEventTypeFromBusinessDispatch() {
        listener.onPatientEvent(message());
        verify(consumerSupport).consume(any(Message.class), handlerCaptor.capture());

        assertThatThrownBy(() -> handlerCaptor
                        .getValue()
                        .accept(envelope(
                                PatientMessagingConstants.EVENT_HEALTH_SUMMARY_UPDATED,
                                payload().put("patientId", SINGLE_ID))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自消费事件类型");
        verify(cacheService, never()).evictView(any(Long.class));
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
        return new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
    }
}

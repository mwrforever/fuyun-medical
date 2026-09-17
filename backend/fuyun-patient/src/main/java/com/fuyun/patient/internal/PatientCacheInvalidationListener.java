package com.fuyun.patient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 患者自事件消费侧缓存失效（跨实例两级缓存一致性闭环；本实例写路径已即时 evict）。
 *
 * <p>消费姿态：容器 AUTO 确认 + raw Message + 标准幂等范式（A.5-5/A.5-6，MdmDispatchListener 同款）；
 * 队列经 MessagingGovernance 治理构件声明（q.patient.patient.<event>，事件先登记后订阅——V105 种子）。
 * 六事件订阅成对合规：merged↔split、frozen↔unfrozen 均成对声明（M-25）。
 *
 * <p>归 internal/：容器驱动的模块内入口禁外引；Bean 注册点为 PatientMessagingConfig @Import。
 */
@Slf4j
public class PatientCacheInvalidationListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final PatientCacheService cacheService;

    /** 全参构造器（装配归 PatientMessagingConfig @Import） */
    public PatientCacheInvalidationListener(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec, PatientCacheService cacheService) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.cacheService = cacheService;
    }

    /**
     * 六事件统一消费入口（队列名与 PatientMessagingConfig 声明同源常量拼接，禁手写字面量）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = {
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_UPDATED,
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_MERGED,
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_SPLIT,
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_FROZEN,
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_UNFROZEN,
                "q." + PatientMessagingConstants.MODULE + "." + PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED
            })
    public void onPatientEvent(Message message) {
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接跳过
        if (!idempotencyService.tryAcquire(envelope.eventId(), PatientMessagingConstants.MODULE)) {
            return;
        }
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(),
                envelope.eventType(),
                envelope.producer(),
                envelope.occurredAt(),
                PatientMessagingConstants.MODULE);
        try {
            doEvict(envelope);
            // 标准范式②：成功登记 received_event
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（不遮蔽 e），上抛交容器有界重试耗尽进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 按事件类型派发缓存失效（载荷 patientId 多形态：单 id / 主从对 / 恢复 id+主档 id）。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 事件类型不在本模块自消费全集（队列绑定与常量漂移）时触发——
     *                               按失败处置走 settleFailure 后上抛有界重试，禁止静默吞掉
     */
    private void doEvict(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        switch (envelope.eventType()) {
            case PatientMessagingConstants.EVENT_UPDATED,
                    PatientMessagingConstants.EVENT_FROZEN,
                    PatientMessagingConstants.EVENT_UNFROZEN,
                    PatientMessagingConstants.EVENT_IDENTIFIER_CHANGED ->
                cacheService.evictView(payload.path("patientId").asLong());
            case PatientMessagingConstants.EVENT_MERGED -> {
                cacheService.evictView(payload.path("survivorPatientId").asLong());
                cacheService.evictView(payload.path("mergedPatientId").asLong());
            }
            case PatientMessagingConstants.EVENT_SPLIT -> {
                cacheService.evictView(payload.path("restoredPatientId").asLong());
                cacheService.evictView(payload.path("survivorPatientId").asLong());
            }
            default -> throw new IllegalStateException("未登记的自消费事件类型（队列绑定与常量漂移），禁止经本监听器消费：" + envelope.eventType());
        }
        log.info("自事件缓存失效完成：eventType={}", envelope.eventType());
    }
}

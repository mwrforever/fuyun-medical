package com.fuyun.patient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.patient.cache.PatientCacheService;
import com.fuyun.patient.constants.PatientMessagingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 患者自事件消费侧缓存失效（跨实例两级缓存一致性闭环；本实例写路径已即时 evict）。
 *
 * <p>消费姿态：容器 AUTO 确认 + raw Message，幂等三段式（A.5-5/A.5-6）收敛 common
 * {@link IdempotentConsumerSupport} 标准模板（终审 Minor「消费/缓存失效范式收敛」），本类仅保留
 * 失效派发业务；队列经 MessagingGovernance 治理构件声明（q.patient.patient.&lt;event&gt;，事件先登记后
 * 订阅——V105 种子）。六事件订阅成对合规：merged↔split、frozen↔unfrozen 均成对声明（M-25）。
 *
 * <p>归 internal/：容器驱动的模块内入口禁外引；Bean 注册点为 PatientMessagingConfig @Import。
 */
@Slf4j
public class PatientCacheInvalidationListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final PatientCacheService cacheService;

    /**
     * 全参构造器（装配归 PatientMessagingConfig @Import；IdempotentConsumerSupport 系 common 基类
     * 跨模块多实例 Bean，fuyun-app 上下文与 billing 侧同型双候选，必须 @Qualifier 定绑
     * patientConsumerSupport——第 2 轮审查 P0-1，禁靠自动解析兜底）。
     *
     * @param consumerSupport 幂等消费模板，非空；定绑 PatientMessagingConfig patientConsumerSupport Bean
     * @param cacheService    患者两级缓存服务，非空
     */
    public PatientCacheInvalidationListener(
            @Qualifier("patientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            PatientCacheService cacheService) {
        this.consumerSupport = consumerSupport;
        this.cacheService = cacheService;
    }

    /**
     * 六事件统一消费入口：标准幂等三段式收敛 common IdempotentConsumerSupport（终审 Minor 范式收敛），
     * 本方法仅保留失效派发业务。
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
        consumerSupport.consume(message, this::doEvict);
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

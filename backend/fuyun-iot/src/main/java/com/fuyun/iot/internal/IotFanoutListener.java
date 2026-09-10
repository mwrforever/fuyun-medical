package com.fuyun.iot.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.service.ITelemetryPushService;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 设备状态自事件消费者（B4.3 消费侧，BRIEF-PR4-01 §1.5）：q.iot.iot.device.status-changed
 * 的标准范式消费执行点（本模块自事件——发布→自消费全链，P0 唯一 iot 订阅，演示治理链）。
 *
 * <p><b>确认机制（红线 4，锁定决策 6）</b>：本监听器走 RabbitMQ 容器 <b>AUTO 确认</b>
 * （宪法 A.5-5：监听方法成功返回即由容器确认，失败有界重试耗尽进 fy.dlx）；iot AMQP 主链路
 * （IotAmqpTelemetryConsumer）走 Qpid JMS 客户端确认——两套机制互不相干。幂等分域（红线 9）：
 * 本监听器属 MQ 事件总线域，走 {@link MessageIdempotencyService} 标准范式（Redis NX 前置 +
 * received_event 唯一索引兜底）；AMQP 主链路明细幂等由 iot_telemetry 唯一约束 ON CONFLICT DO
 * NOTHING 承担，两域不得混用。
 *
 * <p>标准幂等范式（含 D-7 回查语义，MessageIdempotencyService javadoc）：tryAcquire false
 * （回查确认已处理）→ return 跳过即 AUTO 确认；业务执行 + recordProcessed 成功登记；业务失败
 * release 释放前置键后重抛（交容器有界重试，耗尽进 fy.dlx）。DictPublishedListener 同构先例。
 *
 * <p>消费后动作（P0，B4.3-b 已接线）：载荷契约解析 + info 日志留痕（deviceId/status/wardId）
 * + <b>STOMP 设备状态主题推送</b>（经 {@link ITelemetryPushService#pushDeviceStatus} 推
 * /topic/iot/device-status/{wardId}，载荷 wardId 为空时推送静默降级）——推送失败按业务失败
 * 处置（释放前置键重抛走有界重试），推送成功后才登记 PROCESSED。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 IotMessagingConfig @Import。
 */
@Slf4j
public class IotFanoutListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final ObjectMapper objectMapper;

    /** STOMP 推送服务：消费后设备状态主题推送的唯一出口（/topic/iot/device-status/{wardId}） */
    private final ITelemetryPushService pushService;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import，backend 宪法 B.1）。
     *
     * @param idempotencyService 消费幂等构件，非空；来源：M20 治理构件装配（接口沉 common，
     *                           实现经 fuyun-app MessagingConfig @Import 已在上下文可用）
     * @param codec              信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param objectMapper       JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     * @param pushService        STOMP 推送服务，非空；来源：fuyun-app IotConfig 装配链
     */
    public IotFanoutListener(
            MessageIdempotencyService idempotencyService,
            EventEnvelopeCodec codec,
            ObjectMapper objectMapper,
            ITelemetryPushService pushService) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
    }

    /**
     * 设备状态事件消费入口：解析信封 → 幂等范式 → 业务留痕 + 成功登记。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_DEVICE_STATUS)
    public void onDeviceStatusChanged(Message message) {
        // 原文进 codec：__TypeId__ 头不作消费依据（CF-1 冻结约定）；不合规信封上抛交有界重试转死信
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), IotMessagingConstants.MODULE)) {
            log.info(
                    "重复投递跳过：consumerModule={}，event_id={}，eventType={}",
                    IotMessagingConstants.MODULE,
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        try {
            doBusiness(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发重复投递）
            idempotencyService.recordProcessed(new ReceivedEventRecord(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer(),
                    envelope.occurredAt(),
                    IotMessagingConstants.MODULE));
        } catch (RuntimeException e) {
            // 标准范式③：失败释放前置键允许重试/重投重新抢占，上抛交容器有界重试耗尽进 fy.dlx
            idempotencyService.release(envelope.eventId(), IotMessagingConstants.MODULE);
            throw e;
        }
    }

    /**
     * 消费业务（P0，B4.3-b 已接线）：载荷契约解析 + 结构化日志留痕 + STOMP 设备状态主题推送。
     *
     * <p>推送语义：经 {@link ITelemetryPushService#pushDeviceStatus} 推
     * /topic/iot/device-status/{wardId}——载荷 wardId 为空时推送静默降级（简报 §1.5"wardId
     * 空则 info 跳过推送"，P0 状态帧契约不含 wardId 属预期场景）；推送失败按业务失败处置
     * （异常上抛由调用方释放前置键重抛交有界重试），推送成功后才执行 recordProcessed 登记。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 DeviceStatusEvent 契约不符（字段缺失或类型错误）——
     *                               按消费失败处置（释放前置键后重抛走死信），禁止静默吞错
     */
    private void doBusiness(EventEnvelope envelope) {
        DeviceStatusEvent event;
        try {
            event = objectMapper.treeToValue(envelope.payload(), DeviceStatusEvent.class);
        } catch (JsonProcessingException e) {
            // 载荷不合规（缺字段/类型错）等同业务失败：上抛由调用方释放前置键，最终转死信留痕
            throw new IllegalStateException("设备状态事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "设备状态自事件消费完成：deviceId={}，status={}，wardId={}，event_id={}，traceId={}",
                event.deviceId(),
                event.status(),
                event.wardId(),
                envelope.eventId(),
                envelope.traceId());
        // STOMP 设备状态主题推送（wardId 空由推送服务静默降级；失败上抛走释放重推，范式③承接）
        pushService.pushDeviceStatus(event);
    }
}

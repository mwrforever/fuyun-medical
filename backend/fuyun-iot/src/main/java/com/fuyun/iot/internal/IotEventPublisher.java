package com.fuyun.iot.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * IoT 模块 MQ 事件发布器（B4.3 扇出侧，BRIEF-PR4-01 §1.5）：设备状态变更事件
 * （iot.device.status-changed，V403 已登记 event_registry）发往 fy.topic 的唯一发送执行点。
 *
 * <p>发布形态（SystemEventPublisher 同模式）：codec 生成合规信封（producer=iot、payload =
 * {@link DeviceStatusEvent} 契约 record）→ convertAndSend（routing key=eventType，
 * CorrelationData 携带 eventId 供确认回调关联）→ info 留痕。traceId 在发布点从 MDC 取值进
 * 信封——本发布器当前唯一调用方为 AMQP 消费线程（MQ 线程无日志上下文），traceId 恒为 null
 * 属预期（EventEnvelopeCodec 契约允许）。
 *
 * <p><b>发布确认回调归属（与简报 §4 的偏差申报）</b>：Spring AMQP 对共享 RabbitTemplate 强制
 * 断言仅支持单一 Confirm/Returns 回调（设第二个不同实例即启动失败，IT 实证）——故全系统回调
 * 由 PR-3 交付的 SystemEventPublisher 构造期统一注册，本发布器<b>不重复注册</b>、复用同一告警
 * 通道：broker nack 或消息不可路由时 error 日志告警（信封 eventId 经 CorrelationData 关联定位、
 * 不可路由退回含 exchange/routingKey/原文截断），P0 不自动重发（outbox 补偿属 P1 治理完整化）。
 * 回调归属整合（如 RabbitTemplateCustomizer 收口治理装配）归 P1，届时本发布器零改动。
 *
 * <p><b>调用时点约束（宪法 A.4.2-7"事务内禁消息发送"）</b>：本发布器仅允许在<b>事务外</b>
 * 调用——调用链 IotAmqpTelemetryConsumer 状态帧处理运行于 AMQP 消费线程（非事务上下文，且
 * IDeviceStatusService.apply 为单语句自原子更新无事务包裹），当前满足约束；P0 不设
 * AFTER_COMMIT 适配，未来任何事务内调用点必须改为事务提交后触发，禁止在 @Transactional
 * 方法内直接调用本发布器。
 *
 * <p>归 internal/ 包：模块内发送设施非对外契约，禁止外部引用（backend 宪法 B.1）；Bean
 * 注册点为 IotMessagingConfig @Import（com.fuyun.iot 不在组件扫描范围）。
 */
@Slf4j
public class IotEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import，backend 宪法 B.1）。不注册 Confirm/Returns
     * 回调——单一回调槽位由 SystemEventPublisher 统一持有（偏差申报见类注释）。
     *
     * @param rabbitTemplate Rabbit 发送模板，非空；来源：Boot 自动装配（correlated confirm + mandatory）
     * @param codec          信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     */
    public IotEventPublisher(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
    }

    /**
     * 设备状态变更事件发布入口（仅限事务外调用，约束见类注释）。
     *
     * <p>执行流程：codec 生成合规信封（payload = 事件契约 record，wardId 可空）→ convertAndSend
     * 至 fy.topic（routing key=eventType）→ info 留痕。
     *
     * @param event 设备状态变更事件，非空；来源：AMQP 消费者状态帧处理（IDeviceStatusService.apply
     *              返回档案 wardId 后，消费者以之补全事件 wardId 构造触发）
     */
    public void publishDeviceStatus(DeviceStatusEvent event) {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY),
                event);
        // CorrelationData 携带 eventId：共享确认回调据此定位失败帧（P0 告警留痕，不自动重发）
        rabbitTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_EXCHANGE,
                envelope.eventType(),
                envelope,
                new CorrelationData(envelope.eventId()));
        log.info(
                "设备状态事件已投递 MQ：eventType={}，deviceId={}，status={}，wardId={}，eventId={}，traceId={}",
                envelope.eventType(),
                event.deviceId(),
                event.status(),
                event.wardId(),
                envelope.eventId(),
                envelope.traceId());
    }
}

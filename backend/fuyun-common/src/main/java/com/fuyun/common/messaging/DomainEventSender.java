package com.fuyun.common.messaging;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 领域事件发送模板（终审 Minor「发布器范式收敛」基类）：System/Patient/Iot 三发布器重复形态
 * （信封工厂 + fy.topic 直发 + CorrelationData(eventId) + info 留痕）的单一实现，
 * billing 自 PR-3 起接入、patient 发布器回改复用。
 *
 * <p>线程安全：无状态（成员均不可变引用），Spring singleton 安全。
 *
 * <p><b>红线：本类不注册 Confirm/Returns 回调</b>——Spring AMQP 对共享 RabbitTemplate 强制
 * 单回调槽位（二次注册启动失败，PR-2 实证），全系统告警通道由 SystemEventPublisher 统一持有；
 * 回调整合归 TASK.md W-11（RabbitTemplateCustomizer）评审，届时本类零改动。
 */
@Slf4j
public class DomainEventSender {

    /** 领域事件主交换机（治理三件套之一，禁私建） */
    private static final String TOPIC_EXCHANGE = "fy.topic";

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /** 生产模块域标识（信封 producer 与日志锚点），如 billing */
    private final String module;

    /**
     * 全参构造器（各模块 MessagingConfig 装配）。
     *
     * @param rabbitTemplate Boot 自动装配发送模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @param module         模块域标识，非空；来源：各模块 MessagingConstants.MODULE
     */
    public DomainEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec, String module) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        this.module = module;
    }

    /**
     * 发布一条领域事件（须在发布者事务提交后调用——@TransactionalEventListener(AFTER_COMMIT) 承载）。
     *
     * @param eventType 三段事件类型（先在 event_registry 登记），非空；同时作路由键
     * @param payload   业务载荷 record（禁敏感明文），非空
     * @param traceId   全链路追踪号，可空（MQ/异步线程无 MDC 时传 null）
     */
    public void send(String eventType, Object payload, String traceId) {
        EventEnvelope envelope = codec.create(Clock.systemUTC(), module, eventType, traceId, payload);
        // CorrelationData 携带 eventId：共享确认回调据此定位失败帧（告警留痕，不自动重发）
        rabbitTemplate.convertAndSend(TOPIC_EXCHANGE, eventType, envelope, new CorrelationData(envelope.eventId()));
        log.info(
                "领域事件已投递 MQ：module={}，eventType={}，eventId={}，traceId={}",
                module,
                eventType,
                envelope.eventId(),
                envelope.traceId());
    }
}

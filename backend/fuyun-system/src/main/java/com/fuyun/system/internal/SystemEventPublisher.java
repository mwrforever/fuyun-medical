package com.fuyun.system.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.system.api.DictPublishedPayload;
import com.fuyun.system.api.PracticeChangedPayload;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.constants.SystemMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 系统模块 MQ 事件发布器（B3.2 发布侧，BRIEF-PR3-01 §3.2）：字典发布广播的唯一发送执行点。
 *
 * <p>事务时机（B.3-1/A.4.2-7）：监听 {@link DictVersionPublishedEvent}（发布事务上下文内的
 * Spring 应用事件），{@code @TransactionalEventListener(AFTER_COMMIT)} 保证事务提交后才发 MQ
 * ——发布事务回滚则广播不出，杜绝"库未发布而广播已出"；MQ 回调线程无日志上下文，traceId
 * 在 HTTP 线程发布点从 MDC 取值进信封（§1.4 口径）。
 *
 * <p>发布确认（A.5-4）：构造期向共享 RabbitTemplate 注册 Confirm/Returns 回调——broker nack
 * 或消息不可路由时 error 日志告警（含 eventId/路由三要素），P0 不自动重发（outbox 补偿属
 * P1 治理完整化，B.3-3；MessagingGovernanceConfig javadoc 已预告）。
 *
 * <p>归 internal/ 包：模块内发送设施非对外契约，禁止外部引用（backend 宪法 B.1）；Bean
 * 注册点为 SystemMessagingConfig @Import（com.fuyun.system 不在组件扫描范围）。
 */
@Slf4j
public class SystemEventPublisher implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    /** 不可路由退回帧的日志原文截断长度：信封 eventId 位于 JSON 头部，200 字符足以定位且防刷屏 */
    private static final int RETURNED_BODY_LOG_PREFIX = 200;

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /**
     * 全参构造器（装配归 SystemMessagingConfig @Import，backend 宪法 B.1），
     * 并完成发布确认回调注册（共享模板的全局副作用，构造期一次性完成）。
     *
     * @param rabbitTemplate Rabbit 发送模板，非空；来源：Boot 自动装配（correlated confirm + mandatory）
     * @param codec          信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     */
    public SystemEventPublisher(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        // A.5-4：全部业务发送走确认回调；本发布器为 system 模块唯一发布点，回调注册收敛于此
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * 字典版本发布广播入口（事务提交后触发）：信封化载荷并发送至 fy.topic。
     *
     * <p>执行流程：codec 生成合规信封（producer=system、traceId 取 MDC）→ convertAndSend
     * （routing key=eventType，CorrelationData 携带 eventId 供确认回调关联）→ info 留痕。
     * AFTER_COMMIT 回调仍运行于原 HTTP 线程：不清理 MDC（请求尚在收尾，traceId 归
     * TraceIdFilter 统一清理，此处误清会破坏响应渲染期的链路上下文）。
     *
     * @param event 字典版本发布应用事件，非空；来源：DictVersionServiceImpl.publish 事务内发布
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDictVersionPublished(DictVersionPublishedEvent event) {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_DICT_PUBLISHED,
                MDC.get(SecurityConstants.TRACE_ID_MDC_KEY),
                new DictPublishedPayload(event.typeCode(), event.version()));
        // CorrelationData 携带 eventId：confirm/return 回调据此定位失败帧（P0 告警留痕，不自动重发）
        rabbitTemplate.convertAndSend(
                SystemMessagingConstants.TOPIC_EXCHANGE,
                envelope.eventType(),
                envelope,
                new CorrelationData(envelope.eventId()));
        log.info(
                "字典发布广播已投递 MQ：eventType={}，dictType={}，version={}，eventId={}，traceId={}",
                envelope.eventType(),
                event.typeCode(),
                event.version(),
                envelope.eventId(),
                envelope.traceId());
    }

    /**
     * 执业授权变更广播入口（事务提交后触发）：grant 登记/withdraw 停权的事实经信封化载荷
     * 发送至 fy.topic（system.practice.changed，V5 id 6 既有登记）。
     *
     * <p>{@code fallbackExecution = true}：grant/withdraw 均在事务上下文内发布事件，此处兜底
     * 非事务调用路径（事件照发，AFTER_COMMIT 语义在无事务时退化为立即执行），与 outpatient 侧
     * 发布器同型。信封化与留痕执行流程同 {@link #onDictVersionPublished}。
     *
     * @param event 执业授权变更应用事件，非空；来源：PracticeServiceImpl.grant/withdraw 事务内发布
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPracticeChanged(PracticeChangedEvent event) {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_PRACTICE_CHANGED,
                MDC.get(SecurityConstants.TRACE_ID_MDC_KEY),
                new PracticeChangedPayload(event.employeeId(), event.grantType(), event.status()));
        // CorrelationData 携带 eventId：confirm/return 回调据此定位失败帧（告警留痕，不自动重发）
        rabbitTemplate.convertAndSend(
                SystemMessagingConstants.TOPIC_EXCHANGE,
                envelope.eventType(),
                envelope,
                new CorrelationData(envelope.eventId()));
        log.info(
                "执业授权变更广播已投递 MQ：eventType={}，employeeId={}，grantType={}，status={}，eventId={}，traceId={}",
                envelope.eventType(),
                event.employeeId(),
                event.grantType(),
                event.status(),
                envelope.eventId(),
                envelope.traceId());
    }

    /**
     * broker 确认回调：nack 时 error 告警（ack=true 静默返回防高频刷屏）。
     *
     * @param correlationData 确认关联数据，可空（极端场景 broker 未回带）；含发布时携带的 eventId
     * @param ack             true=broker 已接收；false=nack（broker 内部故障等）
     * @param cause           nack 原因描述，可空
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            return;
        }
        // P0 不自动重发（outbox 属 P1）：error 日志即告警通道，eventId 供人工排查与手工重放
        String eventId = correlationData != null ? correlationData.getId() : null;
        log.error("消息发布未获 broker 确认（nack）：eventId={}，cause={}", eventId, cause);
    }

    /**
     * 不可路由退回回调（mandatory=true 生效）：error 告警，携带路由定位三要素与原文截断。
     *
     * @param returned 退回消息载体（exchange/routingKey/replyText/message），非空
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        Message message = returned.getMessage();
        String body = message != null && message.getBody() != null
                ? new String(message.getBody(), StandardCharsets.UTF_8)
                : "";
        // P0 不自动重发：error 告警 + 原文截断留痕（信封 eventId 在 JSON 头部，截断保留可定位）
        log.error(
                "消息不可路由被退回：exchange={}，routingKey={}，replyText={}，bodyPrefix={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText(),
                body.substring(0, Math.min(body.length(), RETURNED_BODY_LOG_PREFIX)));
    }
}

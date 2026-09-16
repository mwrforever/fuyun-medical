package com.fuyun.patient.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.patient.constants.PatientMessagingConstants;
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
 * 患者域 MQ 事件发布器（八事件唯一发送执行点；照抄 SystemEventPublisher 范式，B.3-1/A.4.2-7）。
 *
 * <p>事务时机：监听事务内 Spring 应用事件 {@link PatientDomainEvent}，AFTER_COMMIT 保证事务提交后
 * 才发 MQ——业务事务回滚则广播不出；MQ 回调线程无日志上下文，traceId 在 HTTP 线程发布点从 MDC 取值。
 *
 * <p>D-8 兜底（TASK.md 登记项已裁决消化）：fallbackExecution=true——无活动事务的发布点也立即执行，
 * 仅供补挂端点的 controller 无事务编排（attach 落库提交后才调 publishChanged，即时发布实为「提交后」
 * 语义）；有事务发布点（卡操作/合并/冻结/健康档案等）不受影响仍走 AFTER_COMMIT，事务回滚广播不出。
 *
 * <p>发布确认（A.5-4）：构造期向共享 RabbitTemplate 注册 Confirm/Returns 回调——nack/不可路由
 * error 告警（含 eventId），P0 不自动重发（Modulith 注册表重投承载 in-JVM；跨进程重发随 M20 治理演进）。
 *
 * <p>归 internal/：模块内发送设施禁外引（B.1）；Bean 注册点为 PatientMessagingConfig @Import。
 * 不使用 @Externalized（宪法 B.3-2 未定稿，TASK.md W-11 评审项）。
 */
@Slf4j
public class PatientEventPublisher implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    /** 不可路由退回帧日志原文截断长度（eventId 位于 JSON 头部，200 字符足定位防刷屏） */
    private static final int RETURNED_BODY_LOG_PREFIX = 200;

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致；字面量随 system SecurityConstants 同源——
     *  跨模块取常量须依赖 system api，此处以本地常量镜像并注释锚点，禁散落第二处裸字符串） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /**
     * 全参构造器（装配归 PatientMessagingConfig @Import），并完成发布确认回调注册
     * （共享模板全局副作用，构造期一次性完成）。
     *
     * <p>回调覆盖语义（审查 I4，TASK.md W-11 并项评审）：共享 RabbitTemplate 的 Confirm/Returns
     * 回调为单槽位——SystemEventPublisher 与本类后注册者覆盖前者（bean 初始化顺序漂移），
     * nack/不可路由告警的承载方不固定，但告警语义（error 留痕 + eventId 可定位）不受影响；
     * 本 PR 保持现状并在 PR 描述申报，multicast/组合注册归 W-11 评审定稿。
     *
     * @param rabbitTemplate Rabbit 发送模板，非空；来源：Boot 自动装配
     * @param codec          信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     */
    public PatientEventPublisher(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    /**
     * 患者域事件统一发布入口：有活动事务的发布点在事务提交后触发（AFTER_COMMIT）；无事务发布点
     * 经 D-8 兜底立即触发（调用方保证底库已提交，如补挂端点 attach 返回后编排）。
     *
     * @param event 模块内应用事件（eventType + api payload record），非空；来源：各业务服务发布点
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPatientDomainEvent(PatientDomainEvent event) {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                PatientMessagingConstants.MODULE,
                event.eventType(),
                MDC.get(TRACE_ID_MDC_KEY),
                event.payload());
        // CorrelationData 携带 eventId：确认回调据此定位失败帧（告警留痕，不自动重发）
        rabbitTemplate.convertAndSend(
                PatientMessagingConstants.TOPIC_EXCHANGE,
                envelope.eventType(),
                envelope,
                new CorrelationData(envelope.eventId()));
        log.info(
                "患者域事件已投递 MQ：eventType={}，eventId={}，traceId={}",
                envelope.eventType(),
                envelope.eventId(),
                envelope.traceId());
    }

    /**
     * broker 确认回调：nack 时 error 告警（ack=true 静默返回防刷屏）。
     *
     * @param correlationData 确认关联数据，可空；含发布时携带的 eventId
     * @param ack             true=broker 已接收；false=nack
     * @param cause           nack 原因，可空
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            return;
        }
        String eventId = correlationData != null ? correlationData.getId() : null;
        log.error("患者域事件发布未获 broker 确认（nack）：eventId={}，cause={}", eventId, cause);
    }

    /**
     * 不可路由退回回调（mandatory=true 生效）：error 告警 + 原文截断留痕。
     *
     * @param returned 退回消息载体，非空
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        Message message = returned.getMessage();
        String body = message != null && message.getBody() != null
                ? new String(message.getBody(), StandardCharsets.UTF_8)
                : "";
        log.error(
                "患者域事件不可路由被退回：exchange={}，routingKey={}，replyText={}，bodyPrefix={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText(),
                body.substring(0, Math.min(body.length(), RETURNED_BODY_LOG_PREFIX)));
    }
}

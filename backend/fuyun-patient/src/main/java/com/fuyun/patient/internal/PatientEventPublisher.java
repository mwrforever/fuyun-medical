package com.fuyun.patient.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 患者域 MQ 事件发布器（八事件唯一发送执行点；发送编排照抄 SystemEventPublisher 范式，
 * B.3-1/A.4.2-7）。
 *
 * <p>事务时机：监听事务内 Spring 应用事件 {@link PatientDomainEvent}，AFTER_COMMIT 保证事务提交后
 * 才发 MQ——业务事务回滚则广播不出；MQ 回调线程无日志上下文，traceId 在 HTTP 线程发布点从 MDC 取值。
 *
 * <p>D-8 兜底（TASK.md 登记项已裁决消化）：fallbackExecution=true——无活动事务的发布点也立即执行，
 * 仅供补挂端点的 controller 无事务编排（attach 落库提交后才调 publishChanged，即时发布实为「提交后」
 * 语义）；有事务发布点（卡操作/合并/冻结/健康档案等）不受影响仍走 AFTER_COMMIT，事务回滚广播不出。
 *
 * <p><b>发布确认回调归属（Task 14 真栈冒烟实证修订，同 IotEventPublisher 既定范式）</b>：Spring AMQP
 * 对共享 RabbitTemplate 强制断言仅支持单一 Confirm/Returns 回调（设第二个不同实例即启动失败）——
 * 故全系统回调由 SystemEventPublisher 构造期统一注册，本发布器<b>不重复注册</b>、复用同一告警通道：
 * broker nack 或消息不可路由时 error 日志告警（信封 eventId 经 CorrelationData 关联定位），P0 不自动
 * 重发（Modulith 注册表重投承载 in-JVM；跨进程重发随 M20 治理演进；回调归属整合归 P1
 * RabbitTemplateCustomizer 收口，届时本发布器零改动）。
 *
 * <p>归 internal/：模块内发送设施禁外引（B.1）；Bean 注册点为 PatientMessagingConfig @Import。
 * 不使用 @Externalized（宪法 B.3-2 未定稿，TASK.md W-11 评审项）。
 */
@Slf4j
public class PatientEventPublisher {

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致；字面量随 system SecurityConstants 同源——
     *  跨模块取常量须依赖 system api，此处以本地常量镜像并注释锚点，禁散落第二处裸字符串） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /**
     * 全参构造器（装配归 PatientMessagingConfig @Import）。不注册 Confirm/Returns 回调——单一回调
     * 槽位由 SystemEventPublisher 统一持有（归属依据见类注释「发布确认回调归属」）。
     *
     * @param rabbitTemplate Rabbit 发送模板，非空；来源：Boot 自动装配
     * @param codec          信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     */
    public PatientEventPublisher(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
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
        // CorrelationData 携带 eventId：SystemEventPublisher 持有的共享确认回调据此定位失败帧（告警留痕，不自动重发）
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
}

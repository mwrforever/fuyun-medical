package com.fuyun.billing.internal;

import com.fuyun.common.messaging.DomainEventSender;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 收费域 MQ 事件发布器（CF-4 六事件唯一发送执行点；发送编排收敛于 common
 * {@link DomainEventSender}，Task 5 PatientEventPublisher 同款范式）：本类仅保留
 * 「AFTER_COMMIT 时机 + traceId MDC 取值」两件事，模块标识由 billingEventSender Bean 供给。
 *
 * <p>事务时机：监听事务内 Spring 应用事件 {@link BillingDomainEvent}，AFTER_COMMIT 保证事务提交后
 * 才发 MQ——结算回滚不误放行发药、调价回滚不误刷工作站缓存；D-8 兜底 fallbackExecution=true——
 * 无活动事务的发布点也立即执行（调用方保证底库已提交）。
 *
 * <p>确认回调归属红线：Spring AMQP 对共享 RabbitTemplate 强制单回调槽位（二次注册启动失败，
 * PR-2 实证）——全系统 Confirm/Returns 回调由 SystemEventPublisher 统一注册持有，本发布器不注册、
 * 复用同一告警通道（broker nack 经共享回调 error 告警，eventId 由 CorrelationData 关联定位，
 * 不自动重发）；回调整合归 TASK.md W-11，届时本类零改动。
 *
 * <p>归 internal/：模块内发送设施禁外引（B.1）；Bean 注册点 BillingMessagingConfig @Import。
 * 不使用 @Externalized（宪法 B.3-2 未定稿，TASK.md W-11 评审项）。
 */
@Slf4j
public class BillingEventPublisher {

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致；Patient/Iot 发布器同款镜像锚点注释，
     *  禁散落第二处裸字符串） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final DomainEventSender sender;

    /**
     * 全参构造器（装配归 BillingMessagingConfig @Import；DomainEventSender 系 common 基类跨模块
     * 多实例 Bean，fuyun-app 上下文双候选，必须 @Qualifier 定绑 billingEventSender——第 2 轮审查
     * P0-1，Global Constraints common 模板类红线；单测按位置构造零改动）。不注册任何
     * Confirm/Returns 回调——单槽位红线见类注释。
     *
     * @param sender 发送模板，非空；定绑 BillingMessagingConfig billingEventSender Bean
     */
    public BillingEventPublisher(@Qualifier("billingEventSender") DomainEventSender sender) {
        this.sender = sender;
    }

    /**
     * 收费域事件统一发布入口：有活动事务的发布点在事务提交后触发（AFTER_COMMIT）；无事务发布点
     * 经 D-8 兜底立即触发。
     *
     * @param event 模块内应用事件（eventType + api payload record），非空；来源：各业务服务发布点
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBillingDomainEvent(BillingDomainEvent event) {
        sender.send(event.eventType(), event.payload(), MDC.get(TRACE_ID_MDC_KEY));
    }
}

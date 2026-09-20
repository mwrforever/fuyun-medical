package com.fuyun.outpatient.internal;

import com.fuyun.common.messaging.DomainEventSender;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 门诊域 MQ 事件发布器（CF-3/CF-5 发布事件唯一发送执行点；PharmacyEventPublisher 同款范式）：
 * 仅保留「AFTER_COMMIT 时机 + traceId MDC 取值」，模块标识由 outpatientEventSender Bean 供给。
 * 不注册 Confirm/Returns 回调（共享单槽位归 SystemEventPublisher，TASK.md W-11）；
 * 归 internal/：模块内发送设施禁外引；Bean 注册点 OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientEventPublisher {

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致；全仓发布器镜像锚点，禁散落第二处） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final DomainEventSender sender;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；DomainEventSender 系 common 基类
     * 跨模块多实例 Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 outpatientEventSender
     * ——Global Constraints common 模板类红线；单测按位置构造零改动）。
     *
     * @param sender 发送模板，非空；定绑 OutpatientMessagingConfig outpatientEventSender Bean
     */
    public OutpatientEventPublisher(@Qualifier("outpatientEventSender") DomainEventSender sender) {
        this.sender = sender;
    }

    /**
     * 门诊域事件统一发布入口：有活动事务的发布点在事务提交后触发（AFTER_COMMIT）；无事务
     * 发布点经 D-8 兜底立即触发。
     *
     * @param event 模块内应用事件（eventType + api payload record），非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOutpatientDomainEvent(OutpatientDomainEvent event) {
        sender.send(event.eventType(), event.payload(), MDC.get(TRACE_ID_MDC_KEY));
    }
}

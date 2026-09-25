package com.fuyun.inpatient.internal;

import com.fuyun.common.messaging.DomainEventSender;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 住院域 MQ 事件发布器（CF-6 发布事件唯一发送执行点；NursingEventPublisher 同款范式）：
 * 仅保留「AFTER_COMMIT 时机 + traceId MDC 取值」，模块标识由 inpatientEventSender Bean 供给。
 * 不注册 Confirm/Returns 回调（GC8——共享单槽位归 SystemEventPublisher）；
 * 归 internal/：模块内发送设施禁外引；Bean 注册点 InpatientMessagingConfig @Import。
 */
@Slf4j
public class InpatientEventPublisher {

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致；全仓发布器镜像锚点，禁散落第二处） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final DomainEventSender sender;

    /**
     * 全参构造器（装配归 InpatientMessagingConfig @Import；DomainEventSender 系 common 基类
     * 跨模块多实例 Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 inpatientEventSender
     * ——GC7 common 模板类多实例定绑红线；单测按位置构造零改动）。
     *
     * @param sender 发送模板，非空；定绑 InpatientMessagingConfig inpatientEventSender Bean
     */
    public InpatientEventPublisher(@Qualifier("inpatientEventSender") DomainEventSender sender) {
        this.sender = sender;
    }

    /**
     * 住院域事件统一发布入口：有活动事务的发布点在事务提交后触发（AFTER_COMMIT，GC8 事件事务红线）；
     * 无事务发布点经 fallbackExecution 兜底立即触发。
     *
     * @param event 模块内应用事件（eventType + api/payload record），非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInpatientDomainEvent(InpatientDomainEvent event) {
        sender.send(event.eventType(), event.payload(), MDC.get(TRACE_ID_MDC_KEY));
    }
}

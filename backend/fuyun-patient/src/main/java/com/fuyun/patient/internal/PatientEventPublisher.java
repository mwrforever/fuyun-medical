package com.fuyun.patient.internal;

import com.fuyun.common.messaging.DomainEventSender;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 患者域 MQ 事件发布器（八事件唯一发送执行点）。发送编排收敛至 common
 * {@link DomainEventSender}（终审 Minor「发布器范式收敛」，PR-3 billing 接入时回改）：
 * 本类仅保留「AFTER_COMMIT 时机 + traceId MDC 取值 + 模块常量」三件事。
 *
 * <p>事务时机：监听事务内 Spring 应用事件 {@link PatientDomainEvent}，AFTER_COMMIT 保证提交后才发
 * MQ——回滚则广播不出；D-8 兜底 fallbackExecution=true 语义沿用（Task 14 定稿）。
 *
 * <p>确认回调归属：模板类红线——全系统单槽位回调由 SystemEventPublisher 统一持有
 * （TASK.md W-11），本发布器不注册。归 internal/，Bean 注册点 PatientMessagingConfig @Import。
 */
public class PatientEventPublisher {

    /** traceId MDC 键（与 fuyun.trace.mdc-key 一致，镜像锚点见 BillingMessagingConstants 同款注释） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final DomainEventSender sender;

    /**
     * 全参构造器（装配归 PatientMessagingConfig @Import；DomainEventSender 系 common 基类跨模块
     * 多实例 Bean，fuyun-app 上下文与 billingEventSender 同型双候选，必须 @Qualifier 定绑
     * patientEventSender——第 2 轮审查 P0-1，Global Constraints common 模板类红线；既有单测按位置
     * 构造传 mock 零改动）。
     *
     * @param sender 发送模板，非空；定绑 PatientMessagingConfig patientEventSender Bean
     */
    public PatientEventPublisher(@Qualifier("patientEventSender") DomainEventSender sender) {
        this.sender = sender;
    }

    /**
     * 患者域事件统一发布入口（语义与收敛前完全一致，CF-3 契约零变更）。
     *
     * @param event 模块内应用事件（eventType + api payload record），非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPatientDomainEvent(PatientDomainEvent event) {
        sender.send(event.eventType(), event.payload(), MDC.get(TRACE_ID_MDC_KEY));
    }
}

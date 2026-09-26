package com.fuyun.iot.internal;

import com.fuyun.common.messaging.DomainEventSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * IoT 域 MQ 事件发布器（CF-7 发布事件唯一发送执行点；InpatientEventPublisher 同款范式）：
 * 仅保留「AFTER_COMMIT 时机 + 事件携带上下文透传」，模块标识由 iotEventSender Bean 供给。
 * 不注册 Confirm/Returns 回调（GC8——共享单槽位归 SystemEventPublisher）；
 * 归 internal/：模块内发送设施禁外引；Bean 注册点 IotMessagingConfig @Import。
 *
 * <p>traceId 透传事件携带值而非取 AFTER_COMMIT 时点 MDC：CF-7 发布点多为 AMQP 消费/异步线程，
 * 事务提交回调执行时日志上下文已不可依赖，发布点构造 {@link IotDomainEvent} 时捕获才不丢链路
 * （IotEventPublisher 承载的设备状态扇出为事务外直发路径，与本发布器并行不悖）。
 */
@Slf4j
public class IotDomainPublisher {

    private final DomainEventSender sender;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import；DomainEventSender 系 common 基类
     * 跨模块多实例 Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 iotEventSender
     * ——GC7 common 模板类多实例定绑红线；单测按位置构造零改动）。
     *
     * @param sender 发送模板，非空；定绑 IotMessagingConfig iotEventSender Bean
     */
    public IotDomainPublisher(@Qualifier("iotEventSender") DomainEventSender sender) {
        this.sender = sender;
    }

    /**
     * IoT 域事件统一发布入口：有活动事务的发布点在事务提交后触发（AFTER_COMMIT，GC8 事件事务红线）；
     * 无事务发布点经 fallbackExecution 兜底立即触发。
     *
     * @param event 模块内应用事件（eventType + api/payload 载荷 + 发布点捕获的 occurredAt/traceId），
     *              非空
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIotDomainEvent(IotDomainEvent event) {
        sender.send(event.eventType(), event.payload(), event.traceId());
    }
}

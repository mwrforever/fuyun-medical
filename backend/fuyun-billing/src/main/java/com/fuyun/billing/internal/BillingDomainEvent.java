package com.fuyun.billing.internal;

/**
 * 收费域模块内应用事件载体（B.3-1：事务内发 Spring 应用事件，发布器 AFTER_COMMIT 转 MQ）：
 * Task 11–15 各服务发布点统一经 ApplicationEventPublisher 发布本事件，禁任何服务层直触发送器。
 *
 * <p>归 internal/：模块内设施非对外契约；跨进程契约是 {@code com.fuyun.billing.api} 的 payload
 * record 与 BillingMessagingConstants 事件类型字面量（V605 三方同源），本载体只做「事务时机」搬运。
 *
 * @param eventType 事件类型（BillingMessagingConstants.EVENT_*，先经 V605 登记）；来源：业务服务发布点
 * @param payload   api 包 payload record 实例，非空（禁敏感明文）；来源：业务服务组装
 */
public record BillingDomainEvent(String eventType, Object payload) {}

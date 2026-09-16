package com.fuyun.patient.internal;

/**
 * 患者域模块内应用事件载体（B.3-1：事务内发 Spring 应用事件，发布器 AFTER_COMMIT 转 MQ）。
 *
 * <p>归 internal/：模块内设施非对外契约；跨进程契约是 {@code com.fuyun.patient.api} 的 payload
 * record 与 PatientMessagingConstants 事件类型字面量，本载体只做「事务时机」搬运。
 *
 * @param eventType 事件类型（PatientMessagingConstants.EVENT_*）；来源：业务服务发布点
 * @param payload   api 包 payload record 实例，非空；来源：业务服务组装
 */
public record PatientDomainEvent(String eventType, Object payload) {}

package com.fuyun.inpatient.internal;

/**
 * 住院域模块内应用事件（事务内发布 → InpatientEventPublisher AFTER_COMMIT 出 MQ；
 * NursingDomainEvent 同型——事务内禁直发 MQ 红线的进程内桥）。
 *
 * @param eventType 事件字面量（InpatientMessagingConstants 发布事件全集），非空
 * @param payload   业务载荷 record（api/payload 包冻结契约，禁敏感明文），非空
 */
public record InpatientDomainEvent(String eventType, Object payload) {}

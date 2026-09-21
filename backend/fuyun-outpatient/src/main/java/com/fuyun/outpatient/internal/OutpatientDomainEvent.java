package com.fuyun.outpatient.internal;

/**
 * 门诊域模块内应用事件（事务内发布 → OutpatientEventPublisher AFTER_COMMIT 出 MQ；
 * PharmacyDomainEvent 同型——事务内禁直发 MQ 红线的进程内桥）。
 *
 * @param eventType 事件字面量（OutpatientMessagingConstants 发布事件全集），非空
 * @param payload   业务载荷 record（api 包冻结契约，禁敏感明文），非空
 */
public record OutpatientDomainEvent(String eventType, Object payload) {}

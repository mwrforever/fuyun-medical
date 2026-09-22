package com.fuyun.nursing.internal;

/**
 * 护理域模块内应用事件（事务内发布 → NursingEventPublisher AFTER_COMMIT 出 MQ；
 * OutpatientDomainEvent 同型——事务内禁直发 MQ 红线的进程内桥）。
 *
 * @param eventType 事件字面量（NursingMessagingConstants 发布事件全集），非空
 * @param payload   业务载荷 record（api 包冻结契约，禁敏感明文），非空
 */
public record NursingDomainEvent(String eventType, Object payload) {}

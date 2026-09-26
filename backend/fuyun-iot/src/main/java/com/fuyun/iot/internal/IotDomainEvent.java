package com.fuyun.iot.internal;

import java.time.Instant;

/**
 * IoT 域模块内应用事件（事务内发布 → IotDomainPublisher AFTER_COMMIT 出 MQ；InpatientDomainEvent
 * 同型——事务内禁直发 MQ 红线的进程内桥）。
 *
 * <p>较 inpatient 两字段形态多携带 occurredAt/traceId：CF-7 发布点多为 AMQP 消费/异步线程，
 * AFTER_COMMIT 回调执行时点 MDC 上下文不可依赖，故两值由发布点构造本事件时捕获、监听器仅透传
 * （traceId 进信封；occurredAt 为发布侧统一业务时点锚——载荷内时间字段之外的判定/触发时刻，
 * 供调用方日志与补偿场景取用，信封时点仍由 codec 服务器时钟生成）。
 *
 * @param eventType  事件字面量（IotMessagingConstants 发布事件全集），非空
 * @param payload    业务载荷 record（api/payload 包冻结契约，禁敏感明文），非空
 * @param occurredAt 业务发生时刻（UTC），非空；来源：发布点捕获（设备帧时点或引擎判定时点）
 * @param traceId    全链路追踪号，可空（MQ/异步线程发布点无日志上下文为 null，信封契约允许）；
 *                   来源：发布点 MDC 捕获
 */
public record IotDomainEvent(String eventType, Object payload, Instant occurredAt, String traceId) {}

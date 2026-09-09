package com.fuyun.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 事件信封（CF-1 冻结载体）：全系统跨模块领域事件的唯一发布/消费契约形态。
 *
 * <p>本类即 CF-1 事件信封冻结形态（M20 Spec §3.2 信封七字段全集）：全系统事件必须经信封发布，
 * 新增信封字段属宪法/Spec 修订，禁止业务侧自行扩展透传字段。
 *
 * <p>线格式约定（UTF-8 JSON）：occurredAt 序列化为 ISO-8601 UTC 字符串；payload 为嵌套 JSON 对象；
 * contentType 固定 application/json；__TypeId__ 消息头不作消费依据——消费方一律以 String 承接后经
 * {@link EventEnvelopeCodec} 解析，防类型映射耦合。JSON 反序列化由 Jackson record 内建支持
 * （Boot 3.5 内建），无需 @JsonCreator 定制。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变），equals/hashCode 直接采用 record 语义。
 *
 * @param eventId        事件全局唯一标识（UUID），由工厂生成；消费幂等键第一要素，非空
 * @param occurredAt     事件发生时刻（Clock 注入生成，UTC 语义）；来源：系统生成，非空
 * @param producer       生产模块域标识（如 system）；来源：发布方模块，非空
 * @param eventType      事件类型 {@code <模块>.<实体>.<动作>}（如 system.dict.published）；
 *                       须先在 event_registry 登记后才可发布/订阅，非空
 * @param payloadVersion 载荷契约版本，默认 "1" 随 CF-1 冻结；来源：系统生成，非空
 * @param traceId        全链路追踪号，可空（HTTP 线程内发布取 MDC 当前值，MQ 线程无值传 null）
 * @param payload        业务载荷（Jackson 泛型载体 JsonNode），非空；来源：发布方业务数据
 */
public record EventEnvelope(
        String eventId,
        Instant occurredAt,
        String producer,
        String eventType,
        String payloadVersion,
        String traceId,
        JsonNode payload) {}

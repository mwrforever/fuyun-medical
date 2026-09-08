package com.fuyun.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 事件信封编解码器：全系统事件发布/消费的唯一线格式出入口（CF-1 契约执行点）。
 *
 * <p>职责：create 按时钟注入生成合规信封（可测性：单测用 {@link Clock#fixed} 断言确定性）；
 * toJson 序列化为线格式；fromJson 反序列化并执行消费侧信封合规校验（M20 §3.2 流程①）——
 * 不合规（eventId 非 UUID、producer/eventType/payloadVersion/occurredAt/payload 缺失）抛
 * {@link IllegalArgumentException}，供消费方拒绝并转死信留痕；异常消息只含字段名不含敏感值。
 *
 * <p>线程安全：无状态单例；构造注入的 ObjectMapper 为 Boot 全局定制实例
 * （Long→String 定制经 JacksonLongToStringConfig 生效），消息侧与 REST 侧序列化行为一致。
 *
 * <p>traceId 取值约定：HTTP 线程内发布由调用方传入 {@code MDC.get("traceId")}；MQ 线程无
 * 日志上下文传 null。工场对 producer/eventType/payload 做创建期 fail-fast，不产出先天不合规信封。
 */
@Component
public class EventEnvelopeCodec {

    /** 信封载荷契约版本：当前唯一合法取值，随 CF-1 冻结 */
    private static final String PAYLOAD_VERSION = "1";

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器。
     *
     * @param objectMapper JSON 序列化器，非空；来源：Boot 自动装配的全局定制实例
     *                     （须与 JacksonLongToStringConfig 定制同源，保证 Long→String 一致生效）
     */
    public EventEnvelopeCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建合规事件信封：生成 UUID eventId 与时钟注入的发生时刻，payload 经全局序列化器转 JsonNode。
     *
     * <p>执行流程：必填项 fail-fast 校验 → payload 转 JsonNode（信封不感知具体业务载荷类型）→
     * 生成 UUID eventId 与 {@code Instant.now(clock)} → 组装 payloadVersion="1" 的信封。
     *
     * @param clock     时钟源，非空；生产传 Clock.systemUTC()，单测传 Clock.fixed 保证确定性
     * @param producer  生产模块域标识，非空（如 system）；来源：发布方模块
     * @param eventType 事件类型，非空且须已登记（{@code <模块>.<实体>.<动作>}）
     * @param traceId   全链路追踪号，可空；来源：调用方从 MDC 取当前值
     * @param payload   业务载荷，非空；来源：发布方业务数据（经 valueToTree 转换，兼容 record/Map/POJO）
     * @return 合规事件信封；payloadVersion 固定 "1"
     * @throws IllegalArgumentException producer/eventType 为空白或 payload 为 null 时触发；
     *                                  建议处理策略：调用方修正入参，属编程错误不应重试
     */
    public EventEnvelope create(Clock clock, String producer, String eventType, String traceId, Object payload) {
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("事件信封创建失败：producer 不能为空");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("事件信封创建失败：eventType 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("事件信封创建失败：payload 不能为空");
        }
        // payload 统一转 JsonNode：信封对业务载荷类型零耦合，消费侧按契约 record 二次解析
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                Instant.now(clock),
                producer,
                eventType,
                PAYLOAD_VERSION,
                traceId,
                payloadNode);
    }

    /**
     * 序列化信封为线格式 JSON（UTF-8）。
     *
     * @param envelope 事件信封，非空；来源：create 产物或消费侧解析产物
     * @return 线格式 JSON 字符串；occurredAt 为 ISO-8601 UTC 字符串
     * @throws IllegalStateException 序列化失败时触发（含 eventType 便于定位）；理论仅信封含
     *                               不可序列化类型时发生，建议处理策略：修复载荷类型定义
     */
    public String toJson(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // 只携带 eventType 便于定位，不携带载荷内容防敏感信息入日志
            throw new IllegalStateException("事件信封序列化失败：eventType=" + envelope.eventType(), e);
        }
    }

    /**
     * 反序列化线格式 JSON 并执行信封合规校验（消费侧流程①）。
     *
     * <p>校验项：eventId 可解析为 UUID；producer/eventType/payloadVersion 非空；occurredAt 非空；
     * payload 非空。不合规即拒绝消费并转死信留痕（M20 红线 1）。
     *
     * @param json 线格式 JSON 字符串，非空；来源：MQ 消息体（消费方按 String 承接）
     * @return 合规事件信封
     * @throws IllegalArgumentException JSON 无法解析或信封缺必填字段时触发；建议处理策略：
     *                                  消费方捕获后拒收该帧并留痕死信，不得静默丢弃
     */
    public EventEnvelope fromJson(String json) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            // 非 JSON 报文等同信封不合规（毒丸帧），统一按拒绝消费处理；只带解析摘要不带原文
            throw new IllegalArgumentException("事件信封不合规：JSON 解析失败（" + e.getOriginalMessage() + "）", e);
        }
        validateCompliance(envelope);
        return envelope;
    }

    /**
     * 信封合规校验：逐项断言 CF-1 必填字段，任一缺失即拒绝。
     *
     * @param envelope 待校验信封，非空
     * @throws IllegalArgumentException 首个不合规字段触发；消息含字段名便于消费侧留痕
     */
    private void validateCompliance(EventEnvelope envelope) {
        if (envelope.eventId() == null || !isParseableUuid(envelope.eventId())) {
            throw new IllegalArgumentException("事件信封不合规：eventId 缺失或不是合法 UUID");
        }
        if (envelope.producer() == null || envelope.producer().isBlank()) {
            throw new IllegalArgumentException("事件信封不合规：producer 缺失");
        }
        if (envelope.eventType() == null || envelope.eventType().isBlank()) {
            throw new IllegalArgumentException("事件信封不合规：eventType 缺失");
        }
        if (envelope.payloadVersion() == null || envelope.payloadVersion().isBlank()) {
            throw new IllegalArgumentException("事件信封不合规：payloadVersion 缺失");
        }
        if (envelope.occurredAt() == null) {
            throw new IllegalArgumentException("事件信封不合规：occurredAt 缺失");
        }
        if (envelope.payload() == null || envelope.payload().isNull()) {
            throw new IllegalArgumentException("事件信封不合规：payload 缺失");
        }
    }

    /**
     * 判定字符串可否解析为 UUID。
     *
     * @param value 待判定字符串，非空
     * @return true 表示可解析；false 表示格式非法
     */
    private boolean isParseableUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

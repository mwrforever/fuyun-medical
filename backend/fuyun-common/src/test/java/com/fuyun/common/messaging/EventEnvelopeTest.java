package com.fuyun.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件信封 record 单元测试：验证 CF-1 冻结契约的字段携带合规性与 record 相等语义。
 *
 * <p>工厂产物断言使用固定 Clock（backend 宪法 A.1：时钟注入可测性），保证 occurredAt 可精确断言。
 */
class EventEnvelopeTest {

    /** 固定时刻：工厂产物 occurredAt 的确定性断言锚点 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-09T08:00:00Z");

    @Test
    @DisplayName("固定 Clock 工厂创建：eventId 可解析 UUID、occurredAt 等于固定时刻、producer/type/version 正确")
    void factoryCreatesCompliantEnvelopeFieldsWithFixedClock() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("dictType", "gender");
        EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper());

        EventEnvelope envelope = codec.create(
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), "system", "system.dict.published", "trace-fixed", payload);

        // eventId 必须可解析为 UUID（消费幂等键第一要素，非法值会污染 received_event 唯一索引）
        assertThatCode(() -> UUID.fromString(envelope.eventId())).doesNotThrowAnyException();
        assertThat(envelope.occurredAt()).isEqualTo(FIXED_INSTANT);
        assertThat(envelope.producer()).isEqualTo("system");
        assertThat(envelope.eventType()).isEqualTo("system.dict.published");
        assertThat(envelope.payloadVersion()).isEqualTo("1");
        assertThat(envelope.traceId()).isEqualTo("trace-fixed");
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("record equals 语义：两实例全部字段相同即相等，任一字段不同即不等")
    void recordEqualityFollowsAllComponentFields() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("k", "v");
        EventEnvelope base =
                new EventEnvelope("id-1", FIXED_INSTANT, "system", "system.dict.published", "1", "t-1", payload);
        EventEnvelope sameFields =
                new EventEnvelope("id-1", FIXED_INSTANT, "system", "system.dict.published", "1", "t-1", payload);
        EventEnvelope differentEventId =
                new EventEnvelope("id-2", FIXED_INSTANT, "system", "system.dict.published", "1", "t-1", payload);

        // 契约载体采用 record 透明浅不可变语义（backend 宪法 A.1-6），禁止手写 equals/hashCode
        assertThat(base).isEqualTo(sameFields).hasSameHashCodeAs(sameFields);
        assertThat(base).isNotEqualTo(differentEventId);
    }
}

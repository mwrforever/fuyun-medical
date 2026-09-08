package com.fuyun.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.config.JacksonLongToStringConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 事件信封编解码器单元测试：验证工厂产物合规性、线格式往返无损与消费侧信封合规校验。
 *
 * <p>测试用 ObjectMapper 经 {@link Jackson2ObjectMapperBuilder} 构建（默认关闭 WRITE_DATES_AS_TIMESTAMPS，
 * 与 Boot 自动装配行为一致），保证 occurredAt 以 ISO-8601 字符串进出；Long→String 定制场景
 * 复用 {@link JacksonLongToStringConfig} 的定制器，验证消息侧与 REST 侧序列化行为一致。
 */
class EventEnvelopeCodecTest {

    /** 固定时刻：工厂产物的确定性断言锚点 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-09T08:30:00Z");

    /** 与 Boot 默认姿态一致的序列化器（WRITE_DATES_AS_TIMESTAMPS 关闭），供编解码器与样本构造共用 */
    private final ObjectMapper objectMapper = bootMirroredMapper();

    private EventEnvelopeCodec codec;

    @BeforeEach
    void setUp() {
        codec = new EventEnvelopeCodec(objectMapper);
    }

    /**
     * 构建与 Boot 自动装配默认行为一致的 ObjectMapper：关闭时间戳形态（Instant 输出 ISO-8601 字符串），
     * 保证测试断言的线格式与生产 ObjectMapper 输出一致。
     *
     * @return Boot 姿态镜像的 ObjectMapper
     */
    private ObjectMapper bootMirroredMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return builder.build();
    }

    @Test
    @DisplayName("create→toJson→fromJson 往返：信封七字段全部一致，occurredAt 线格式为 ISO-8601")
    void roundTripPreservesAllEnvelopeFields() {
        ObjectNode payload =
                JsonNodeFactory.instance.objectNode().put("dictType", "gender").put("version", 3);
        EventEnvelope created = codec.create(
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), "system", "system.dict.published", "trace-rt-1", payload);

        String json = codec.toJson(created);
        // 线格式冻结：occurredAt 必须为 ISO-8601 UTC 字符串（CF-1 线格式约定），不得退化为时间戳数值
        assertThat(json).contains("\"occurredAt\":\"2026-09-09T08:30:00Z\"");
        assertThat(codec.fromJson(json)).isEqualTo(created);
    }

    @Test
    @DisplayName("payload 复杂嵌套（List/中文）往返无损")
    void roundTripPreservesComplexNestedPayload() {
        Map<String, Object> payload = Map.of(
                "dictItems", List.of(Map.of("code", "M", "label", "男"), Map.of("code", "F", "label", "女")), "版本", 3);
        EventEnvelope created = codec.create(
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), "system", "system.dict.published", null, payload);

        EventEnvelope parsed = codec.fromJson(codec.toJson(created));

        assertThat(parsed.payload()).isEqualTo(objectMapper.valueToTree(payload));
    }

    @Test
    @DisplayName("payload 中的 Long 字段经全局定制序列化为 JSON 字符串（消息侧与 REST 侧行为一致）")
    void serializesPayloadLongAsStringWithGlobalCustomization() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonLongToStringConfig().longToStringCustomizer().customize(builder);
        EventEnvelopeCodec customizedCodec = new EventEnvelopeCodec(builder.build());

        EventEnvelope envelope = customizedCodec.create(
                Clock.systemUTC(), "system", "system.dict.published", null, new LongSamplePayload(123L, 999L));

        String json = customizedCodec.toJson(envelope);
        // 超出 JS 2^53 安全整数范围的雪花 ID/金额分值必须以字符串承载（backend 宪法 A.3-8）
        assertThat(json).contains("\"orderId\":\"123\"").contains("\"amount\":\"999\"");
    }

    @Test
    @DisplayName("fromJson 缺 producer：抛 IllegalArgumentException 并指明不合规字段")
    void rejectsEnvelopeMissingProducer() throws Exception {
        assertThatThrownBy(() -> codec.fromJson(envelopeJsonWithout("producer")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("producer");
    }

    @Test
    @DisplayName("fromJson 缺 eventType：抛 IllegalArgumentException")
    void rejectsEnvelopeMissingEventType() throws Exception {
        assertThatThrownBy(() -> codec.fromJson(envelopeJsonWithout("eventType")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("fromJson 缺 payloadVersion：抛 IllegalArgumentException")
    void rejectsEnvelopeMissingPayloadVersion() throws Exception {
        assertThatThrownBy(() -> codec.fromJson(envelopeJsonWithout("payloadVersion")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadVersion");
    }

    @Test
    @DisplayName("fromJson 缺 occurredAt：抛 IllegalArgumentException")
    void rejectsEnvelopeMissingOccurredAt() throws Exception {
        assertThatThrownBy(() -> codec.fromJson(envelopeJsonWithout("occurredAt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("fromJson 缺 payload：抛 IllegalArgumentException")
    void rejectsEnvelopeMissingPayload() throws Exception {
        assertThatThrownBy(() -> codec.fromJson(envelopeJsonWithout("payload")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("fromJson 非法 eventId（非 UUID）：抛 IllegalArgumentException")
    void rejectsEnvelopeWithNonUuidEventId() throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(validEnvelopeJson());
        node.put("eventId", "not-a-uuid");

        assertThatThrownBy(() -> codec.fromJson(objectMapper.writeValueAsString(node)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("create 对空 producer/空 eventType/null payload fail-fast（不产出先天不合规信封）")
    void createRejectsBlankRequiredFields() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("k", "v");

        assertThatThrownBy(() -> codec.create(clock, " ", "system.dict.published", null, payload))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.create(clock, "system", " ", null, payload))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.create(clock, "system", "system.dict.published", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造合规信封线格式 JSON，供各不合规场景删除字段后复用。
     *
     * @return 合规信封 JSON 字符串
     * @throws Exception JSON 解析失败（测试样本构造错误时触发）
     */
    private String validEnvelopeJson() throws Exception {
        EventEnvelope envelope = sampleEnvelope();
        return objectMapper.writeValueAsString(envelope);
    }

    /**
     * 构造删除指定字段后的信封 JSON：模拟线上缺失必填字段的场景。
     *
     * @param removedFields 需要从线格式中移除的字段名
     * @return 删除字段后的 JSON 字符串
     * @throws Exception JSON 解析失败（测试样本构造错误时触发）
     */
    private String envelopeJsonWithout(String... removedFields) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(validEnvelopeJson());
        for (String field : removedFields) {
            node.remove(field);
        }
        return objectMapper.writeValueAsString(node);
    }

    private EventEnvelope sampleEnvelope() {
        return codec.create(
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                "system",
                "system.dict.published",
                "t-1",
                JsonNodeFactory.instance.objectNode().put("k", "v"));
    }

    /** 测试样本：携带包装 Long 与原生 long 的载荷 record，仅用于验证序列化线格式 */
    record LongSamplePayload(Long orderId, long amount) {}
}

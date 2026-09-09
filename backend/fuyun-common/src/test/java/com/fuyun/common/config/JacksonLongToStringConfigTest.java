package com.fuyun.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Long→String 全局序列化定制器单元测试：验证雪花 ID/金额分值的精度防线与时间字段回归防护。
 *
 * <p>backend 宪法 A.3-8：Long/long 统一以字符串输出，超出 JS 2^53 安全整数范围的值不得经 JSON
 * number 承载；该配置为全项目唯一注册点，禁止各接口零散处理。
 */
class JacksonLongToStringConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 定制器应用于独立 builder 后构建 ObjectMapper，等价 Boot 自动装配对定制器的消费路径；
        // 关闭时间戳形态与 Boot 默认姿态一致（Boot 自动配置默认禁用 WRITE_DATES_AS_TIMESTAMPS，Instant 输出 ISO-8601）
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonLongToStringConfig().longToStringCustomizer().customize(builder);
        objectMapper = builder.build();
    }

    @Test
    @DisplayName("包装 Long 序列化为 JSON 字符串：123L 产出 \"123\" 而非数字 123")
    void serializesBoxedLongAsString() throws Exception {
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(Map.of("id", 123L, "amount", 999L)));

        assertThat(node.get("id").isTextual()).isTrue();
        assertThat(node.get("id").asText()).isEqualTo("123");
        assertThat(node.get("amount").isTextual()).isTrue();
        assertThat(node.get("amount").asText()).isEqualTo("999");
    }

    @Test
    @DisplayName("原生 long 字段同样转字符串（Long.TYPE 注册生效）")
    void serializesPrimitiveLongAsString() throws Exception {
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(new PrimitiveLongSample(5L)));

        assertThat(node.get("count").isTextual()).isTrue();
        assertThat(node.get("count").asText()).isEqualTo("5");
    }

    @Test
    @DisplayName("Instant 仍为 ISO-8601 字符串（回归防护，防误伤时间字段）")
    void keepsInstantAsIso8601String() throws Exception {
        JsonNode node = objectMapper.readTree(
                objectMapper.writeValueAsString(Map.of("occurredAt", Instant.parse("2026-09-09T08:00:00Z"))));

        assertThat(node.get("occurredAt").isTextual()).isTrue();
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-09-09T08:00:00Z");
    }

    /** 测试样本：携带原生 long 字段的数据载体（验证 Long.TYPE 序列化器注册路径） */
    record PrimitiveLongSample(long count) {}
}

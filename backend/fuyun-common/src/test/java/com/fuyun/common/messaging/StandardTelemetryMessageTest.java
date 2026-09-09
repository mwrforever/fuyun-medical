package com.fuyun.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * CF-7 标准遥测消息模型单元测试：冻结七字段语义（M14 FU-M14-05 遥测七要素）与 JSON 往返形态。
 *
 * <p>本模型为遥测移交 SPI 的签名载体（P0 只登记不消费，SPI 与消费链路随 PR-4 交付）；
 * 线格式断言保证 occurredAt 以 ISO-8601 字符串进出，与事件信封线格式姿态一致。
 */
class StandardTelemetryMessageTest {

    /** 固定时刻：occurredAt 的确定性断言锚点 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-09T08:00:00Z");

    /** 与 Boot 默认姿态一致的序列化器（WRITE_DATES_AS_TIMESTAMPS 关闭），保证 Instant 输出 ISO-8601 字符串 */
    private final ObjectMapper objectMapper = bootMirroredMapper();

    @Test
    @DisplayName("七字段语义：字段值一一对应遥测七要素，record 同值即相等")
    void recordCarriesSevenTelemetryFieldsWithRecordEquality() {
        StandardTelemetryMessage base = new StandardTelemetryMessage(
                "dev-icu-001", "MDC_HEART_RATE", "72", "bpm", FIXED_INSTANT, "GOOD", "IOTDA");
        StandardTelemetryMessage sameFields = new StandardTelemetryMessage(
                "dev-icu-001", "MDC_HEART_RATE", "72", "bpm", FIXED_INSTANT, "GOOD", "IOTDA");
        StandardTelemetryMessage differentQuality = new StandardTelemetryMessage(
                "dev-icu-001", "MDC_HEART_RATE", "72", "bpm", FIXED_INSTANT, "SUSPECT", "IOTDA");

        // 契约载体采用 record 透明浅不可变语义（backend 宪法 A.1-6），禁止手写 equals/hashCode
        assertThat(base).isEqualTo(sameFields).hasSameHashCodeAs(sameFields);
        assertThat(base).isNotEqualTo(differentQuality);
        assertThat(base.deviceId()).isEqualTo("dev-icu-001");
        assertThat(base.metricCode()).isEqualTo("MDC_HEART_RATE");
        assertThat(base.value()).isEqualTo("72");
        assertThat(base.unit()).isEqualTo("bpm");
        assertThat(base.occurredAt()).isEqualTo(FIXED_INSTANT);
        assertThat(base.quality()).isEqualTo("GOOD");
        assertThat(base.source()).isEqualTo("IOTDA");
    }

    @Test
    @DisplayName("JSON 往返无损：occurredAt 以 ISO-8601 字符串进出，quality/source 取值约定保留")
    void jsonRoundTripPreservesAllFields() throws Exception {
        // quality=SUSPECT（时间偏差超阈值标注不丢弃）与 source=HL7（模式 D 辅链路）为质量字段约定取值样本
        StandardTelemetryMessage message =
                new StandardTelemetryMessage("dev-ward-002", "MDC_SPO2", "97", "%", FIXED_INSTANT, "SUSPECT", "HL7");

        JsonNode node = objectMapper.valueToTree(message);
        assertThat(node.get("occurredAt").isTextual()).isTrue();
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-09-09T08:00:00Z");
        // 原始值为字符串承载（数值解析定型随 PR-4），线格式中不丢原始形态
        assertThat(node.get("value").asText()).isEqualTo("97");

        assertThat(objectMapper.treeToValue(node, StandardTelemetryMessage.class))
                .isEqualTo(message);
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
}

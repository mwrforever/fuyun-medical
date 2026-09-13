package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 毒丸留痕脱敏器单元测试（TASK.md L-3 行自带义务：iot_consume_error_log.raw_payload 禁止原文含
 * 生命体征数值等健康数据入库，终审 Minor 2026-09-11）。
 *
 * <p>覆盖三分型：①IoTDA 推送形态白名单提取（属性值全打码、键名与白名单结构保留、白名单外字段
 * 丢弃）；②非 JSON/其他形态 SensitiveMasker 正则兜底（手机号/身份证掩码命中，先证后机组合约定）；
 * ③null/空串原样返回。脱敏器为 static 纯函数，测试直调无夹具装配。
 */
class ConsumePayloadMaskerTest {

    /** 断言侧 JSON 解析器（校验白名单输出结构），非空 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("IoTDA 推送形态：属性值全打码星号，键名与白名单结构保留，白名单外字段丢弃")
    void iotdaPushPayloadIsWhitelistSanitized() throws Exception {
        String raw = """
                {"resource":"device.property","event":"report","event_time_ms":"2026-09-12T17:30:41.632Z",
                 "notify_data":{"header":{"device_id":"6aa570ac155456566827c784_fuyun-demo-001",
                 "node_id":"fuyun-demo-001","product_id":"6aa570ac155456566827c784"},
                 "body":{"services":[{"service_id":"Monitor","properties":{"heartRate":78,"spo2":100},
                 "event_time":"20260912T173041Z"}]}}}
                """;

        String sanitized = ConsumePayloadMasker.sanitize(raw);

        JsonNode output = MAPPER.readTree(sanitized);
        // 白名单结构保留：顶层 resource/event/event_time_ms + header 三标识 + services[].service_id
        assertThat(output.path("resource").asText()).isEqualTo("device.property");
        assertThat(output.path("event").asText()).isEqualTo("report");
        assertThat(output.path("event_time_ms").asText()).isEqualTo("2026-09-12T17:30:41.632Z");
        JsonNode header = output.path("notify_data").path("header");
        assertThat(header.path("device_id").asText()).isEqualTo("6aa570ac155456566827c784_fuyun-demo-001");
        assertThat(header.path("node_id").asText()).isEqualTo("fuyun-demo-001");
        assertThat(header.path("product_id").asText()).isEqualTo("6aa570ac155456566827c784");
        JsonNode service =
                output.path("notify_data").path("body").path("services").get(0);
        assertThat(service.path("service_id").asText()).isEqualTo("Monitor");
        // 属性键名保留、属性值一律替换为 "*"（生命体征数值禁止入库）；白名单外字段丢弃
        JsonNode properties = service.path("properties");
        assertThat(properties.path("heartRate").asText()).as("属性值 heartRate 打码").isEqualTo("*");
        assertThat(properties.path("spo2").asText()).as("属性值 spo2 打码").isEqualTo("*");
        assertThat(properties.size()).as("仅保留原属性键名，不增不减").isEqualTo(2);
        assertThat(service.has("event_time"))
                .as("白名单外字段（services[].event_time）丢弃")
                .isFalse();
        assertThat(sanitized).as("健康数据数值不得残留于脱敏产物").doesNotContain(":78");
    }

    @Test
    @DisplayName("非 JSON 文本：手机号与身份证正则掩码命中（先证后机组合约定）")
    void nonJsonTextFallsBackToRegexMasking() {
        String raw = "故障帧样本13812345678证号11010119900101123X";

        String sanitized = ConsumePayloadMasker.sanitize(raw);

        assertThat(sanitized)
                .as("手机号保留前 3 后 4、身份证保留前 6 后 4（SensitiveMasker 组合约定）")
                .contains("138****5678")
                .contains("110101********123X")
                .doesNotContain("13812345678")
                .doesNotContain("11010119900101123X");
    }

    @Test
    @DisplayName("普通 CF-7 JSON：无 IoTDA 形态特征走正则兜底，无命中不改结构与内容")
    void cf7JsonFallsBackToRegexWithoutStructureChange() {
        String raw = "{\"deviceId\":\"dev-01\",\"metricCode\":\"vital.heart-rate\",\"value\":\"72\","
                + "\"occurredAt\":\"2026-09-10T04:00:00Z\"}";

        String sanitized = ConsumePayloadMasker.sanitize(raw);

        assertThat(sanitized).as("CF-7 P0 契约无 PHI，正则不命中即原样返回").isEqualTo(raw);
    }

    @Test
    @DisplayName("null 与空串原样返回（无内容可脱敏）")
    void nullAndEmptyTextAreReturnedAsIs() {
        assertThat(ConsumePayloadMasker.sanitize(null)).isNull();
        assertThat(ConsumePayloadMasker.sanitize("")).isEmpty();
    }
}

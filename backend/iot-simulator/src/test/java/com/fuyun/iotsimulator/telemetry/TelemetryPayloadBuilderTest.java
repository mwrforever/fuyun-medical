package com.fuyun.iotsimulator.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 物模型上行载荷构造器单测（BRIEF-PR4-01 §5）：properties/report JSON 结构与属性键、
 * 确定性伪随机体征序列同 seed 可回放（联调比对口径）。
 */
class TelemetryPayloadBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("JSON 结构：services[0].service_id=Monitor，properties 携带 heartRate/spo2 且落在体征值域")
    void buildsMonitorServicePropertiesReport() throws Exception {
        TelemetryPayloadBuilder builder = new TelemetryPayloadBuilder(42L);
        JsonNode payload = MAPPER.readTree(builder.next());

        JsonNode service = payload.path("services").get(0);
        assertThat(service.path("service_id").asText()).as("物模型服务标识（P0 演示字面量）").isEqualTo("Monitor");
        int heartRate = service.path("properties").path("heartRate").asInt();
        int spo2 = service.path("properties").path("spo2").asInt();
        assertThat(heartRate).as("心率演示值域 60~100 bpm").isBetween(60, 100);
        assertThat(spo2).as("血氧演示值域 95~100 %").isBetween(95, 100);
    }

    @Test
    @DisplayName("同 seed 回放一致：两个同 seed 构造器各取 50 帧逐帧相同（确定性序列）")
    void sameSeedReplaysIdenticalSequence() {
        TelemetryPayloadBuilder first = new TelemetryPayloadBuilder(42L);
        TelemetryPayloadBuilder second = new TelemetryPayloadBuilder(42L);
        for (int i = 0; i < 50; i++) {
            assertThat(first.next()).as("第 " + (i + 1) + " 帧同 seed 必须逐字符一致").isEqualTo(second.next());
        }
    }

    @Test
    @DisplayName("同设备同序列：以 deviceId 派生 seed（String.hashCode 规范稳定），跨构造器可回放")
    void sameDeviceYieldsReplayableSequence() {
        TelemetryPayloadBuilder fromDeviceId = new TelemetryPayloadBuilder("dev-001");
        TelemetryPayloadBuilder fromEquivalentSeed = new TelemetryPayloadBuilder("dev-001".hashCode());
        assertThat(fromDeviceId.next()).isEqualTo(fromEquivalentSeed.next());
    }

    @Test
    @DisplayName("显式体征组帧：指定心率/血氧值原样写入 Monitor 服务属性（剧本引擎组帧入口）")
    void buildsFrameWithExplicitVitals() throws Exception {
        TelemetryPayloadBuilder builder = new TelemetryPayloadBuilder(42L);
        JsonNode payload = MAPPER.readTree(builder.next(88, 97));

        JsonNode properties = payload.path("services").get(0).path("properties");
        assertThat(properties.path("heartRate").asInt()).as("显式心率值直写").isEqualTo(88);
        assertThat(properties.path("spo2").asInt()).as("显式血氧值直写").isEqualTo(97);
        assertThat(properties.has("infusionRate"))
                .as("双参重载不携输液指标（输液指标仅 infusion 剧本输出）")
                .isFalse();
    }

    @Test
    @DisplayName("输液五指标帧：体征 + 滴速/余量/体温同服务 Monitor 展开，数值一位小数保真")
    void buildsInfusionFrameWithFiveMetrics() throws Exception {
        TelemetryPayloadBuilder builder = new TelemetryPayloadBuilder(42L);
        JsonNode payload = MAPPER.readTree(builder.next(110, 96, 16.0, 20.0, 36.5));

        JsonNode properties = payload.path("services").get(0).path("properties");
        assertThat(properties.path("heartRate").asInt()).as("应激心率值域值直写").isEqualTo(110);
        assertThat(properties.path("spo2").asInt()).as("血氧值直写").isEqualTo(96);
        assertThat(properties.path("infusionRate").asDouble()).as("滴速属性（ml/h）").isEqualTo(16.0);
        assertThat(properties.path("infusionVolumeRemaining").asDouble())
                .as("余量属性（ml）")
                .isEqualTo(20.0);
        assertThat(properties.path("bodyTemp").asDouble()).as("体温属性（℃）").isEqualTo(36.5);
    }

    @Test
    @DisplayName("T-R3-5 帧体实测：输液五指标帧 UTF-8 字节数远低于 512B（单帧远低于 IoTDA 1MB 上限，无需分片）")
    void infusionFrameStaysFarBelowPlatformMessageLimit() {
        TelemetryPayloadBuilder builder = new TelemetryPayloadBuilder(42L);
        byte[] frame = builder.next(110, 96, 16.0, 20.0, 36.5).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(frame.length)
                .as("五指标帧实测字节数（TASK.md T-R3-5 复核锚：官方限制为 MQTT 单条发布 ≤1MB，" + "512B 仅为实例上行速率规格的平均 payload 口径）")
                .isLessThan(512)
                .isGreaterThan(100);
    }
}

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
}

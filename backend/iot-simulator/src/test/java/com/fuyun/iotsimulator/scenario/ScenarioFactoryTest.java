package com.fuyun.iotsimulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iotsimulator.config.SimulatorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 剧本工厂单测（P2 PR-2 Task 14 Step 1）：按配置档产出 vitals/infusion 两类剧本实例——
 * vitals 封装既有体征确定性序列（帧形不变、零档位公告），infusion 产出状态机剧本；
 * 未知档名防御性拒绝（词表权威在 SimulatorConfig 校验，工厂兜底防配置面未来漂移）。
 */
class ScenarioFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("vitals 档：产出既有行为封装——帧仅含体征双指标、无输液属性、零档位公告、不支持命令")
    void createsVitalsScenarioPreservingLegacyBehavior() throws Exception {
        Scenario scenario = ScenarioFactory.create(
                new SimulatorConfig("tcp://127.0.0.1:1883", "dev-001", "secret-001", 5, "vitals", 1.0));

        assertThat(scenario.name()).as("剧本名与配置档一致").isEqualTo("vitals");
        assertThat(scenario).as("vitals 档产出既有行为封装实例").isInstanceOf(VitalsScenario.class);

        JsonNode frame = MAPPER.readTree(scenario.nextFrame());
        JsonNode properties = frame.path("services").get(0).path("properties");
        assertThat(properties.path("heartRate").asInt()).as("既有体征序列：心率 60~100").isBetween(60, 100);
        assertThat(properties.path("spo2").asInt()).as("既有体征序列：血氧 95~100").isBetween(95, 100);
        assertThat(properties.has("infusionRate"))
                .as("输液指标仅 infusion 剧本输出（vitals 帧形零变化）")
                .isFalse();
        assertThat(properties.has("infusionVolumeRemaining"))
                .as("余量属性 vitals 帧不存在")
                .isFalse();
        assertThat(properties.has("bodyTemp")).as("体温属性 vitals 帧不存在").isFalse();
        assertThat(scenario.drainPhaseTransitions()).as("vitals 剧本无档位概念，零公告").isEmpty();
        assertThat(scenario.handleCommand("PAUSE_INFUSION", "{}"))
                .as("vitals 剧本不支持任何命令（回执走失败口径）")
                .isFalse();
    }

    @Test
    @DisplayName("infusion 档：产出状态机剧本实例，帧携输液三指标")
    void createsInfusionStateMachineScenario() throws Exception {
        Scenario scenario = ScenarioFactory.create(
                new SimulatorConfig("tcp://127.0.0.1:1883", "dev-001", "secret-001", 5, "infusion", 1.0));

        assertThat(scenario.name()).as("剧本名与配置档一致").isEqualTo("infusion");
        assertThat(scenario).as("infusion 档产出状态机剧本实例").isInstanceOf(InfusionScenario.class);

        JsonNode properties =
                MAPPER.readTree(scenario.nextFrame()).path("services").get(0).path("properties");
        assertThat(properties.has("infusionRate")).as("输液帧携滴速属性").isTrue();
        assertThat(properties.has("infusionVolumeRemaining")).as("输液帧携余量属性").isTrue();
        assertThat(properties.has("bodyTemp")).as("输液帧携体温属性").isTrue();
    }
}

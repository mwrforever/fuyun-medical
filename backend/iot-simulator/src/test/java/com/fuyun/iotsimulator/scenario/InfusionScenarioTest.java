package com.fuyun.iotsimulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iotsimulator.scenario.InfusionScenario.Phase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 输液剧本状态机确定性单测（P2 PR-2 Task 14 Step 1，先写后落码）：时钟注入（MutableClock）
 * 驱动状态机逐阶段推进——逐阶段指标值域、推进条件、循环回 NORMAL、倍速换算、命令暂停/恢复
 * 全部按固定流逝量确定性断言（禁真实时钟，流逝量不定则状态机断言失去复现性）。
 *
 * <p>确定性推演基准（seed=42、满速 80ml/h、1 小时档推进）：余量 500→420→340→260→180→100
 * （NORMAL）→20（DECAYING，滴速 0.8×余量 线性衰减）→4（LOW≤15ml/CRITICAL≤10ml/≤5ml 同拍连跨）
 * →0（STARVED 断流）→保持 60 仿真秒→循环回 NORMAL（余量复位 500）。各阶段转换逐一断言。
 */
class InfusionScenarioTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定基准时刻（跨运行回放一致；仅作时钟起点，业务语义无含义） */
    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Test
    @DisplayName("首帧即含全部五指标：体征落临床值域、滴速 80ml/h、余量按满速衰减，初始档位公告 NORMAL")
    void firstFrameCarriesFullClinicalMetricsAndAnnouncesNormal() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        clock.advanceSeconds(3600);
        JsonNode frame = MAPPER.readTree(scenario.nextFrame());

        JsonNode properties = frame.path("services").get(0).path("properties");
        assertThat(properties.path("heartRate").asInt())
                .as("心率临床值域（NORMAL）60~100 bpm")
                .isBetween(60, 100);
        assertThat(properties.path("spo2").asInt()).as("血氧临床值域 95~100 %").isBetween(95, 100);
        assertThat(properties.path("infusionRate").asDouble()).as("满速滴速 80ml/h").isEqualTo(80.0);
        assertThat(properties.path("infusionVolumeRemaining").asDouble())
                .as("1 小时满速消耗后余量 420ml（500-80×1h）")
                .isEqualTo(420.0);
        assertThat(properties.path("bodyTemp").asDouble())
                .as("体温演示值域 36.0~37.2 ℃")
                .isBetween(36.0, 37.2);
        assertThat(properties.has("infusionRate") && properties.has("infusionVolumeRemaining"))
                .as("输液指标与体征同服务 Monitor 展开输出")
                .isTrue();
        assertThat(scenario.drainPhaseTransitions()).as("初始档位公告一次 NORMAL").containsExactly("NORMAL");
    }

    @Test
    @DisplayName("满速段推进：余量按 80ml/h 线性消耗（420→340→260→180→100），未跨档零公告")
    void normalPhaseConsumesAtFullRateWithoutTransitions() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        scenario.nextFrame();
        scenario.drainPhaseTransitions();

        // 首帧在零流逝基准上产出（余量 500.0、无消耗），此后每 1 小时按满速递减 80ml
        double[] expectedVolumes = {420.0, 340.0, 260.0, 180.0};
        for (double expected : expectedVolumes) {
            clock.advanceSeconds(3600);
            JsonNode frame = MAPPER.readTree(scenario.nextFrame());
            assertThat(frame.path("services")
                            .get(0)
                            .path("properties")
                            .path("infusionVolumeRemaining")
                            .asDouble())
                    .as("满速段余量确定性消耗")
                    .isEqualTo(expected);
            assertThat(frame.path("services")
                            .get(0)
                            .path("properties")
                            .path("infusionRate")
                            .asDouble())
                    .as("满速段滴速恒 80ml/h")
                    .isEqualTo(80.0);
        }
        assertThat(scenario.drainPhaseTransitions())
                .as("余量 100ml 未低于 DECAYING 阈值，零档位公告")
                .isEmpty();
    }

    @Test
    @DisplayName("整循环档位序列：NORMAL→DECAYING→LOW→CRITICAL(10ml 橙档)→CRITICAL(5ml 红档)→STARVED→循环回 NORMAL")
    void fullCycleAnnouncesEachPhaseTransitionExactlyOnce() {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);

        List<String> allTransitions = new ArrayList<>();
        // 1 小时档推进至 STARVED（推演：t8 进入 STARVED），再续推到循环复位——全序列确定性可复演
        for (int tick = 0; tick < 9; tick++) {
            clock.advanceSeconds(3600);
            scenario.nextFrame();
            allTransitions.addAll(scenario.drainPhaseTransitions());
        }

        assertThat(allTransitions)
                .as("整循环档位公告序列（10ml 橙档与 5ml 红档各公告一帧，STARVED 保持 60 仿真秒后复位）")
                .containsExactly("NORMAL", "DECAYING", "LOW", "CRITICAL", "CRITICAL", "STARVED", "NORMAL");
    }

    @Test
    @DisplayName("DECAYING 线性衰减：滴速=0.8×余量（100ml→80ml/h 连续衔接满速），余量严格递减")
    void decayingPhaseAttenuatesRateLinearlyWithVolume() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        // 推进 5 小时至余量恰好 100.0（仍 NORMAL，阈值判定为 <100），第 6 小时进 DECAYING
        for (int i = 0; i < 5; i++) {
            clock.advanceSeconds(3600);
            scenario.nextFrame();
        }
        scenario.drainPhaseTransitions();
        clock.advanceSeconds(3600);
        JsonNode frame = MAPPER.readTree(scenario.nextFrame());

        JsonNode properties = frame.path("services").get(0).path("properties");
        double volume = properties.path("infusionVolumeRemaining").asDouble();
        double rate = properties.path("infusionRate").asDouble();
        assertThat(volume).as("DECAYING 段余量 20ml（100-80×1h）").isEqualTo(20.0);
        assertThat(rate).as("滴速线性衰减：0.8×20ml=16ml/h（低于满速）").isEqualTo(16.0);
        assertThat(rate).as("衰减段滴速严格低于满速 80ml/h").isLessThan(80.0);
        assertThat(scenario.drainPhaseTransitions()).as("DECAYING 档位公告一次").containsExactly("DECAYING");
    }

    @Test
    @DisplayName("STARVED 断流：滴速 0+余量 0+心率升入 100~120 应激值域，血氧仍处临床值域")
    void starvedPhaseStopsFlowAndRaisesHeartRate() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        // 1 小时档 ×8 推至 STARVED（t8 余量归零）
        for (int i = 0; i < 8; i++) {
            clock.advanceSeconds(3600);
            scenario.nextFrame();
            scenario.drainPhaseTransitions();
        }
        assertThat(scenario.phase()).as("第 8 小时末进入 STARVED").isEqualTo(Phase.STARVED);

        // STARVED 保持窗内按 5 秒档连续取帧：断流指标与应激心率值域逐帧成立（窗 60 仿真秒内）
        for (int i = 0; i < 10; i++) {
            clock.advanceSeconds(5);
            JsonNode frame = MAPPER.readTree(scenario.nextFrame());
            JsonNode properties = frame.path("services").get(0).path("properties");
            assertThat(properties.path("infusionRate").asDouble()).as("断流滴速恒 0").isEqualTo(0.0);
            assertThat(properties.path("infusionVolumeRemaining").asDouble())
                    .as("余量恒 0")
                    .isEqualTo(0.0);
            assertThat(properties.path("heartRate").asInt())
                    .as("应激心率值域 100~120 bpm（断流应激）")
                    .isBetween(100, 120);
            assertThat(properties.path("spo2").asInt()).as("血氧仍处临床值域 95~100 %").isBetween(95, 100);
        }
    }

    @Test
    @DisplayName("STARVED 保持 60 仿真秒后循环复位：余量回 500ml、滴速回满速、心率回临床值域")
    void starvedHoldExpiresAndCycleRestartsToNormal() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        for (int i = 0; i < 8; i++) {
            clock.advanceSeconds(3600);
            scenario.nextFrame();
            scenario.drainPhaseTransitions();
        }
        // 保持窗按 5 秒档耗尽（60 仿真秒 = 12 拍）：前 11 拍仍 STARVED，第 12 拍复位
        for (int i = 0; i < 11; i++) {
            clock.advanceSeconds(5);
            scenario.nextFrame();
            assertThat(scenario.drainPhaseTransitions()).as("保持窗内无新档位公告").isEmpty();
        }
        clock.advanceSeconds(5);
        JsonNode frame = MAPPER.readTree(scenario.nextFrame());

        JsonNode properties = frame.path("services").get(0).path("properties");
        assertThat(properties.path("infusionVolumeRemaining").asDouble())
                .as("复位余量 500ml")
                .isEqualTo(500.0);
        assertThat(properties.path("infusionRate").asDouble())
                .as("复位滴速回满速 80ml/h")
                .isEqualTo(80.0);
        assertThat(properties.path("heartRate").asInt()).as("复位心率回临床值域").isBetween(60, 100);
        assertThat(scenario.drainPhaseTransitions()).as("复位公告 NORMAL（循环回起点）").containsExactly("NORMAL");
    }

    @Test
    @DisplayName("倍速换算：同 seed 同 1 小时真实流逝，speed=2 余量消耗恰为 speed=1 的两倍（340 vs 420）")
    void scenarioSpeedScalesSimulatedElapsedTime() throws Exception {
        MutableClock slowClock = new MutableClock(T0);
        MutableClock fastClock = new MutableClock(T0);
        InfusionScenario slow = new InfusionScenario(42L, 1.0, slowClock);
        InfusionScenario fast = new InfusionScenario(42L, 2.0, fastClock);

        slowClock.advanceSeconds(3600);
        fastClock.advanceSeconds(3600);
        double slowVolume = MAPPER.readTree(slow.nextFrame())
                .path("services")
                .get(0)
                .path("properties")
                .path("infusionVolumeRemaining")
                .asDouble();
        double fastVolume = MAPPER.readTree(fast.nextFrame())
                .path("services")
                .get(0)
                .path("properties")
                .path("infusionVolumeRemaining")
                .asDouble();

        assertThat(slowVolume).as("speed=1：1 小时消耗 80ml").isEqualTo(420.0);
        assertThat(fastVolume).as("speed=2：同真实流逝仿真时长翻倍，消耗 160ml").isEqualTo(340.0);
    }

    @Test
    @DisplayName("暂停命令：滴注冻结（滴速 0、余量定格、体征正常），恢复命令公告底层档位并续耗")
    void pauseCommandFreezesDripAndResumeAnnouncesUnderlyingPhase() throws Exception {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);
        clock.advanceSeconds(3600);
        scenario.nextFrame();
        scenario.drainPhaseTransitions();

        assertThat(scenario.handleCommand("PAUSE_INFUSION", "{}"))
                .as("暂停命令执行成功")
                .isTrue();
        assertThat(scenario.drainPhaseTransitions()).as("暂停公告 PAUSED").containsExactly("PAUSED");

        // 暂停期间流逝 1 小时：余量定格不消耗、滴速 0、心率仍临床值域
        clock.advanceSeconds(3600);
        JsonNode pausedFrame = MAPPER.readTree(scenario.nextFrame());
        JsonNode pausedProperties = pausedFrame.path("services").get(0).path("properties");
        assertThat(pausedProperties.path("infusionVolumeRemaining").asDouble())
                .as("暂停期余量定格 420ml")
                .isEqualTo(420.0);
        assertThat(pausedProperties.path("infusionRate").asDouble())
                .as("暂停期滴速 0")
                .isEqualTo(0.0);
        assertThat(pausedProperties.path("heartRate").asInt()).as("暂停期心率仍临床值域").isBetween(60, 100);
        assertThat(scenario.drainPhaseTransitions()).as("暂停期间零档位公告").isEmpty();

        // 重复暂停幂等：不再重复公告
        assertThat(scenario.handleCommand("PAUSE_INFUSION", "{}"))
                .as("重复暂停幂等成功")
                .isTrue();
        assertThat(scenario.drainPhaseTransitions()).as("重复暂停零公告").isEmpty();

        assertThat(scenario.handleCommand("RESUME_INFUSION", "{}"))
                .as("恢复命令执行成功")
                .isTrue();
        assertThat(scenario.drainPhaseTransitions())
                .as("恢复公告底层档位 NORMAL（PAUSED→NORMAL 档位变化）")
                .containsExactly("NORMAL");

        // 恢复后流逝 1 小时：按满速续耗（420-80=340）
        clock.advanceSeconds(3600);
        JsonNode resumedFrame = MAPPER.readTree(scenario.nextFrame());
        assertThat(resumedFrame
                        .path("services")
                        .get(0)
                        .path("properties")
                        .path("infusionVolumeRemaining")
                        .asDouble())
                .as("恢复后满速续耗")
                .isEqualTo(340.0);
    }

    @Test
    @DisplayName("未支持命令拒绝执行（回执走失败口径）；未暂停时恢复命令幂等零公告")
    void unsupportedCommandRejectedAndResumeWithoutPauseIsNoop() {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 1.0, clock);

        assertThat(scenario.handleCommand("SHUTDOWN_DEVICE", "{}"))
                .as("剧本未支持的命令拒绝执行（回执 result_code=1）")
                .isFalse();
        assertThat(scenario.handleCommand(null, "{}")).as("空命令名拒绝执行").isFalse();
        assertThat(scenario.handleCommand("RESUME_INFUSION", "{}"))
                .as("未暂停时恢复命令幂等成功")
                .isTrue();
        assertThat(scenario.drainPhaseTransitions()).as("幂等恢复零档位公告").isEmpty();
    }

    @Test
    @DisplayName("多阈值同拍连跨安全：单拍大幅流逝时档位按余量降序逐级公告不漏档")
    void largeElapsedTickCrossesAllCheckpointsInDescendingOrder() {
        MutableClock clock = new MutableClock(T0);
        InfusionScenario scenario = new InfusionScenario(42L, 60.0, clock);
        // speed=60：单拍 1 小时真实流逝 = 60 小时仿真流逝，余量 500ml 一拍内耗尽并复位
        clock.advanceSeconds(3600);
        scenario.nextFrame();

        assertThat(scenario.drainPhaseTransitions())
                .as("单拍连跨全档位：公告序仍按余量降序逐级（含 10ml/5ml 各一帧；复位需保持窗另起一拍）")
                .containsExactly("NORMAL", "DECAYING", "LOW", "CRITICAL", "CRITICAL", "STARVED");
    }
}

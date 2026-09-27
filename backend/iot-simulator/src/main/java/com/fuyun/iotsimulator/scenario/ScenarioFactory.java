package com.fuyun.iotsimulator.scenario;

import com.fuyun.iotsimulator.config.SimulatorConfig;
import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import java.time.Clock;

/**
 * 剧本工厂（P2 PR-2 Task 14）：按配置档 {@code IOTDA_SCENARIO} 产出剧本实例——
 * vitals（既有行为封装，缺省档）与 infusion（输液状态机剧本）。
 *
 * <p>词表权威在 {@link SimulatorConfig} 构造期校验（未知档名启动即拒）；工厂的未知档
 * 分支为防御兜底（防配置面未来漂移时静默产出错误剧本），正常装配流不可达。
 * 倍速（IOTDA_SCENARIO_SPEED）仅对含时间推进的剧本生效（infusion），vitals 序列与
 * 时间无关不受倍速影响。
 */
public final class ScenarioFactory {

    /** vitals 档名（SimulatorConfig 词表，缺省档） */
    public static final String SCENARIO_VITALS = "vitals";

    /** infusion 档名（SimulatorConfig 词表，输液状态机剧本） */
    public static final String SCENARIO_INFUSION = "infusion";

    private ScenarioFactory() {}

    /**
     * 按配置档产出剧本实例。
     *
     * @param config 模拟设备配置，非空；scenario/scenarioSpeed 字段决定剧本形态
     * @return 剧本实例，非空；vitals 档为既有行为封装，infusion 档为状态机剧本
     * @throws IllegalStateException 档名不在词表内（防御兜底；正常配置流不可达）
     */
    public static Scenario create(SimulatorConfig config) {
        return switch (config.scenario()) {
            case SCENARIO_VITALS ->
                // 既有行为封装：deviceId 派生 seed 与 P0 逐字符同序列（升级零行为变化锚点）
                new VitalsScenario(new TelemetryPayloadBuilder(config.deviceId()));
            case SCENARIO_INFUSION ->
                new InfusionScenario(config.deviceId().hashCode(), config.scenarioSpeed(), Clock.systemUTC());
            default ->
                throw new IllegalStateException(
                        "未知剧本档名：" + config.scenario() + "（合法词表：" + SCENARIO_VITALS + "/" + SCENARIO_INFUSION + "）");
        };
    }
}

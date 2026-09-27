package com.fuyun.iotsimulator.config;

import java.util.Map;
import java.util.Set;

/**
 * 模拟设备启动配置（BRIEF-PR4-01 §5，D-3 裁决纯 Java 零 Spring）：全部取值来自环境变量
 * （deploy compose iot-simulator 服务 + .env IOTDA_* 变量承载），禁硬编码凭据。
 *
 * <p>变量词表：{@code IOTDA_MQTT_HOST}（必填，MQTT 接入地址，如 ssl://{host}:8883）、
 * {@code IOTDA_DEVICE_ID}（必填，平台注册设备标识）、{@code IOTDA_DEVICE_SECRET}（必填，
 * 一机一密设备密钥）、{@code IOTDA_REPORT_INTERVAL_SECONDS}（可选，上行周期秒数，默认 5）、
 * {@code IOTDA_SCENARIO}（可选，剧本档名 vitals[默认]/infusion，P2 PR-2 Task 14）、
 * {@code IOTDA_SCENARIO_SPEED}（可选，剧本倍速正数，默认 1——仅对含时间推进的 infusion
 * 剧本生效，真实流逝×倍速=仿真流逝）。
 *
 * <p>校验即失败（fail-fast）：必填缺失、周期非正整数、档名不在词表或倍速非正数均在构造期
 * 抛出，错误信息中文指明缺失变量名——模拟设备属演示链路前端，配置残缺时静默启动只会把
 * 故障后移到 MQTT 建链失败。
 */
public record SimulatorConfig(
        String mqttHost,
        String deviceId,
        String deviceSecret,
        int reportIntervalSeconds,
        String scenario,
        double scenarioSpeed) {

    /** MQTT 接入地址环境变量名（必填） */
    public static final String ENV_MQTT_HOST = "IOTDA_MQTT_HOST";

    /** 设备标识环境变量名（必填） */
    public static final String ENV_DEVICE_ID = "IOTDA_DEVICE_ID";

    /** 设备密钥环境变量名（必填，禁入日志） */
    public static final String ENV_DEVICE_SECRET = "IOTDA_DEVICE_SECRET";

    /** 上行周期环境变量名（可选，正整数秒） */
    public static final String ENV_REPORT_INTERVAL = "IOTDA_REPORT_INTERVAL_SECONDS";

    /** 剧本档名环境变量名（可选，vitals/infusion，P2 PR-2 Task 14） */
    public static final String ENV_SCENARIO = "IOTDA_SCENARIO";

    /** 剧本倍速环境变量名（可选，正数，P2 PR-2 Task 14） */
    public static final String ENV_SCENARIO_SPEED = "IOTDA_SCENARIO_SPEED";

    /** 剧本词表：vitals（既有体征循环，缺省档）/ infusion（输液状态机剧本） */
    static final Set<String> SCENARIO_WORDS = Set.of("vitals", "infusion");

    /** 剧本缺省档名：未配置 IOTDA_SCENARIO 时维持 P0 既有行为 */
    static final String DEFAULT_SCENARIO = "vitals";

    /** 剧本倍速默认值：1 倍速 = 真实时钟原速推进 */
    static final double DEFAULT_SCENARIO_SPEED = 1.0;

    /** 上行周期默认值（秒）：演示链路下肉眼可辨又不冲击 IoTDA 演示实例的折中档 */
    static final int DEFAULT_REPORT_INTERVAL_SECONDS = 5;

    /**
     * P0 兼容四参构造（既有调用面与单测锚定）：剧本档取缺省 vitals、倍速取 1——
     * 未感知剧本能力的调用方行为与 P0 完全一致。
     *
     * @param mqttHost              MQTT 接入地址，非空
     * @param deviceId              平台注册设备标识，非空
     * @param deviceSecret          一机一密设备密钥，非空（禁入日志）
     * @param reportIntervalSeconds 上行周期（正整数秒）
     */
    public SimulatorConfig(String mqttHost, String deviceId, String deviceSecret, int reportIntervalSeconds) {
        this(mqttHost, deviceId, deviceSecret, reportIntervalSeconds, DEFAULT_SCENARIO, DEFAULT_SCENARIO_SPEED);
    }

    /**
     * 紧凑构造器校验：必填三项非空非空白、上行周期正整数、剧本档名在词表内（空白归一为
     * 缺省 vitals）、倍速为正数。
     *
     * @throws IllegalStateException 任一必填环境变量缺失或空白（消息含变量名）
     * @throws IllegalArgumentException 上行周期非正整数 / 档名不在词表 / 倍速非正数
     */
    public SimulatorConfig {
        requireNotBlank(mqttHost, ENV_MQTT_HOST, "MQTT 接入地址");
        requireNotBlank(deviceId, ENV_DEVICE_ID, "平台注册设备标识");
        requireNotBlank(deviceSecret, ENV_DEVICE_SECRET, "一机一密设备密钥");
        if (reportIntervalSeconds <= 0) {
            throw new IllegalArgumentException("启动失败：" + ENV_REPORT_INTERVAL + " 必须为正整数秒，实际值=" + reportIntervalSeconds);
        }
        // 档名空白（未配置/显式空串）归一缺省档——env_file 注入空串等价于未配置
        if (scenario == null || scenario.isBlank()) {
            scenario = DEFAULT_SCENARIO;
        } else if (!SCENARIO_WORDS.contains(scenario)) {
            throw new IllegalArgumentException("启动失败：" + ENV_SCENARIO + " 仅支持 vitals/infusion，实际值=" + scenario);
        }
        if (scenarioSpeed <= 0) {
            throw new IllegalArgumentException("启动失败：" + ENV_SCENARIO_SPEED + " 必须为正数倍速，实际值=" + scenarioSpeed);
        }
    }

    /**
     * 从环境变量映射装配配置（static 工厂便于单测注入假 env；生产传 System.getenv()）。
     *
     * @param env 环境变量视图，非空；生产为 System.getenv()，测试为 Map 桩
     * @return 校验通过的模拟设备配置，非空
     * @throws IllegalStateException 必填缺失（消息指明变量名）
     * @throws IllegalArgumentException 周期/倍速非数字或非正值、档名不在词表
     */
    public static SimulatorConfig fromEnv(Map<String, String> env) {
        int interval =
                parsePositiveInt(env.get(ENV_REPORT_INTERVAL), ENV_REPORT_INTERVAL, DEFAULT_REPORT_INTERVAL_SECONDS);
        double speed = parsePositiveDouble(env.get(ENV_SCENARIO_SPEED), ENV_SCENARIO_SPEED, DEFAULT_SCENARIO_SPEED);
        return new SimulatorConfig(
                env.get(ENV_MQTT_HOST),
                env.get(ENV_DEVICE_ID),
                env.get(ENV_DEVICE_SECRET),
                interval,
                env.get(ENV_SCENARIO),
                speed);
    }

    /**
     * 解析可选正整数变量：空白取默认，非数字或非正值以中文错误点名变量拒绝启动。
     *
     * @param raw          变量原值（可空）
     * @param envName      变量名（错误信息定位用）
     * @param defaultValue 空白缺省值
     * @return 解析结果（正整数）
     * @throws IllegalArgumentException 非数字或非正值
     */
    private static int parsePositiveInt(String raw, String envName, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("启动失败：" + envName + " 必须为正整数，实际值=" + raw, e);
        }
    }

    /**
     * 解析可选正数变量（剧本倍速等）：空白取默认，非数字或非正值以中文错误点名变量拒绝启动。
     *
     * @param raw          变量原值（可空）
     * @param envName      变量名（错误信息定位用）
     * @param defaultValue 空白缺省值
     * @return 解析结果（正数）
     * @throws IllegalArgumentException 非数字或非正值
     */
    private static double parsePositiveDouble(String raw, String envName, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("启动失败：" + envName + " 必须为正数，实际值=" + raw, e);
        }
    }

    /**
     * 必填项空白校验：缺失即抛中文错误并点名变量。
     *
     * @param value 待校验值（可空）
     * @param envName 环境变量名（错误信息定位用）
     * @param businessMeaning 变量业务含义（错误信息可读性）
     */
    private static void requireNotBlank(String value, String envName, String businessMeaning) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启动失败：缺少必填环境变量 " + envName + "（" + businessMeaning + "）");
        }
    }
}

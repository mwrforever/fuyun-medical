package com.fuyun.iotsimulator.config;

import java.util.Map;

/**
 * 模拟设备启动配置（BRIEF-PR4-01 §5，D-3 裁决纯 Java 零 Spring）：全部取值来自环境变量
 * （deploy compose iot-simulator 服务 + .env IOTDA_* 六变量承载），禁硬编码凭据。
 *
 * <p>变量词表：{@code IOTDA_MQTT_HOST}（必填，MQTT 接入地址，如 ssl://{host}:8883）、
 * {@code IOTDA_DEVICE_ID}（必填，平台注册设备标识）、{@code IOTDA_DEVICE_SECRET}（必填，
 * 一机一密设备密钥）、{@code IOTDA_REPORT_INTERVAL_SECONDS}（可选，上行周期秒数，默认 5）。
 *
 * <p>校验即失败（fail-fast）：必填缺失或周期非正整数均在构造期抛出，错误信息中文指明缺失
 * 变量名——模拟设备属演示链路前端，配置残缺时静默启动只会把故障后移到 MQTT 建链失败。
 */
public record SimulatorConfig(String mqttHost, String deviceId, String deviceSecret, int reportIntervalSeconds) {

    /** MQTT 接入地址环境变量名（必填） */
    public static final String ENV_MQTT_HOST = "IOTDA_MQTT_HOST";

    /** 设备标识环境变量名（必填） */
    public static final String ENV_DEVICE_ID = "IOTDA_DEVICE_ID";

    /** 设备密钥环境变量名（必填，禁入日志） */
    public static final String ENV_DEVICE_SECRET = "IOTDA_DEVICE_SECRET";

    /** 上行周期环境变量名（可选，正整数秒） */
    public static final String ENV_REPORT_INTERVAL = "IOTDA_REPORT_INTERVAL_SECONDS";

    /** 上行周期默认值（秒）：演示链路下肉眼可辨又不冲击 IoTDA 演示实例的折中档 */
    static final int DEFAULT_REPORT_INTERVAL_SECONDS = 5;

    /**
     * 紧凑构造器校验：必填三项非空非空白、上行周期为正整数。
     *
     * @throws IllegalStateException 任一必填环境变量缺失或空白（消息含变量名）
     * @throws IllegalArgumentException 上行周期非正整数
     */
    public SimulatorConfig {
        requireNotBlank(mqttHost, ENV_MQTT_HOST, "MQTT 接入地址");
        requireNotBlank(deviceId, ENV_DEVICE_ID, "平台注册设备标识");
        requireNotBlank(deviceSecret, ENV_DEVICE_SECRET, "一机一密设备密钥");
        if (reportIntervalSeconds <= 0) {
            throw new IllegalArgumentException("启动失败：" + ENV_REPORT_INTERVAL + " 必须为正整数秒，实际值=" + reportIntervalSeconds);
        }
    }

    /**
     * 从环境变量映射装配配置（static 工厂便于单测注入假 env；生产传 System.getenv()）。
     *
     * @param env 环境变量视图，非空；生产为 System.getenv()，测试为 Map 桩
     * @return 校验通过的模拟设备配置，非空
     * @throws IllegalStateException 必填缺失（消息指明变量名）
     * @throws IllegalArgumentException 周期变量非数字或非正值
     */
    public static SimulatorConfig fromEnv(Map<String, String> env) {
        String intervalRaw = env.get(ENV_REPORT_INTERVAL);
        int interval = DEFAULT_REPORT_INTERVAL_SECONDS;
        if (intervalRaw != null && !intervalRaw.isBlank()) {
            try {
                interval = Integer.parseInt(intervalRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("启动失败：" + ENV_REPORT_INTERVAL + " 必须为正整数秒，实际值=" + intervalRaw, e);
            }
        }
        return new SimulatorConfig(
                env.get(ENV_MQTT_HOST), env.get(ENV_DEVICE_ID), env.get(ENV_DEVICE_SECRET), interval);
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

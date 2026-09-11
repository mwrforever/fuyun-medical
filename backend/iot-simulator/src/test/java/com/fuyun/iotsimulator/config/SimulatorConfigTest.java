package com.fuyun.iotsimulator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模拟设备启动配置单测（BRIEF-PR4-01 §5）：必填缺失启动失败且中文指明缺哪个变量、
 * 上行周期默认值与越界拒绝、合法全量映射。
 */
class SimulatorConfigTest {

    /** 合法最小环境（三必填齐备，周期走默认） */
    private static Map<String, String> minimalValidEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("IOTDA_MQTT_HOST", "ssl://127.0.0.1:1883");
        env.put("IOTDA_DEVICE_ID", "dev-001");
        env.put("IOTDA_DEVICE_SECRET", "secret-001");
        return env;
    }

    @Test
    @DisplayName("缺失必填 IOTDA_MQTT_HOST：启动失败且错误信息指明缺失变量名")
    void failsWhenMqttHostMissing() {
        Map<String, String> env = minimalValidEnv();
        env.remove("IOTDA_MQTT_HOST");
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IOTDA_MQTT_HOST");
    }

    @Test
    @DisplayName("缺失必填 IOTDA_DEVICE_ID / IOTDA_DEVICE_SECRET：错误信息各自指明缺失变量名")
    void failsWithVariableNameWhenDeviceIdentityMissing() {
        Map<String, String> noDeviceId = minimalValidEnv();
        noDeviceId.remove("IOTDA_DEVICE_ID");
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(noDeviceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IOTDA_DEVICE_ID");

        Map<String, String> noSecret = minimalValidEnv();
        noSecret.remove("IOTDA_DEVICE_SECRET");
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(noSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IOTDA_DEVICE_SECRET");
    }

    @Test
    @DisplayName("三必填齐备且周期未配置：上行周期取默认 5 秒且字段逐项映射")
    void appliesDefaultsWhenIntervalAbsent() {
        SimulatorConfig config = SimulatorConfig.fromEnv(minimalValidEnv());
        assertThat(config.mqttHost()).isEqualTo("ssl://127.0.0.1:1883");
        assertThat(config.deviceId()).isEqualTo("dev-001");
        assertThat(config.deviceSecret()).isEqualTo("secret-001");
        assertThat(config.reportIntervalSeconds()).as("上行周期默认值").isEqualTo(5);
    }

    @Test
    @DisplayName("周期超参拒绝：0、负数与非数字均以中文错误拒绝启动")
    void rejectsOutOfRangeInterval() {
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(withInterval(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IOTDA_REPORT_INTERVAL_SECONDS");
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(withInterval(-3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimulatorConfig.fromEnv(withInterval("not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IOTDA_REPORT_INTERVAL_SECONDS");
    }

    @Test
    @DisplayName("周期显式覆盖：合法正整数秒生效")
    void honorsExplicitIntervalOverride() {
        assertThat(SimulatorConfig.fromEnv(withInterval(10)).reportIntervalSeconds())
                .isEqualTo(10);
    }

    /** 在合法最小环境上叠加周期变量（含非法值场景） */
    private static Map<String, String> withInterval(Object value) {
        Map<String, String> env = minimalValidEnv();
        env.put("IOTDA_REPORT_INTERVAL_SECONDS", String.valueOf(value));
        return env;
    }
}

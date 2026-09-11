package com.fuyun.iotsimulator.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一机一密连接三元组编码器单测（BRIEF-PR4-01 §5）。
 *
 * <p>算法核对口径（华为云官方文档《密钥鉴权_MQTT(S)协议接入》iot_02_0203，2026-09-10 核对）：
 * clientId = {deviceId}_0_0_{UTC yyyyMMddHH}、username = deviceId、
 * password = HmacSHA256(key=时间戳, message=deviceSecret) 小写十六进制；
 * 官方示例（secret=12345678、timestamp=2025041401 → c75150e6…820a）以固定时钟逐字符复算回归。
 * 固定 clock 注入保证断言确定性（不依赖真实时间）。
 */
class DeviceCredentialEncoderTest {

    /** 与官方文档示例对齐的固定时刻：UTC 2025-04-14 01 时 → 时间戳 2025041401 */
    private static final Clock DOC_EXAMPLE_CLOCK = Clock.fixed(Instant.parse("2025-04-14T01:23:45Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("三元组格式：clientId 为 deviceId_0_0_yyyyMMddHH、username 等于 deviceId")
    void buildsTripletInOfficialFormat() {
        DeviceCredentialEncoder.MqttCredential credential =
                DeviceCredentialEncoder.encode("dev-001", "secret-001", DOC_EXAMPLE_CLOCK);
        assertThat(credential.clientId())
                .as("clientId 四段下划线格式（签名类型 0=不校验时间戳准确度）")
                .isEqualTo("dev-001_0_0_2025041401");
        assertThat(credential.username()).as("username = deviceId").isEqualTo("dev-001");
    }

    @Test
    @DisplayName("官方文档示例复算：password 与华为云文档原例逐字符一致（key=时间戳，message=secret）")
    void reproducesOfficialDocumentationExample() {
        DeviceCredentialEncoder.MqttCredential credential =
                DeviceCredentialEncoder.encode("any-device", "12345678", DOC_EXAMPLE_CLOCK);
        assertThat(credential.password())
                .as("华为云官方文档示例值（secret=12345678、timestamp=2025041401）")
                .isEqualTo("c75150e6cb841417396819e4d2ee4358a416344a03a083e3a8567074ddec820a");
    }

    @Test
    @DisplayName("签名确定性：同一 clock 两次编码结果完全一致（同 seed 可回放口径）")
    void deterministicForSameClock() {
        DeviceCredentialEncoder.MqttCredential first =
                DeviceCredentialEncoder.encode("dev-001", "secret-001", DOC_EXAMPLE_CLOCK);
        DeviceCredentialEncoder.MqttCredential second =
                DeviceCredentialEncoder.encode("dev-001", "secret-001", DOC_EXAMPLE_CLOCK);
        assertThat(first.clientId()).isEqualTo(second.clientId());
        assertThat(first.password()).isEqualTo(second.password());
    }

    @Test
    @DisplayName("时间戳进签名：跨小时时钟推进后 clientId 与 password 同步变化，且时间戳恒为 10 位小时粒度")
    void timestampAdvancesSignatureAndStaysHourGranularity() {
        DeviceCredentialEncoder.MqttCredential at0100 =
                DeviceCredentialEncoder.encode("dev-001", "secret-001", DOC_EXAMPLE_CLOCK);
        Clock nextHour = Clock.fixed(Instant.parse("2025-04-14T02:00:00Z"), ZoneOffset.UTC);
        DeviceCredentialEncoder.MqttCredential at0200 =
                DeviceCredentialEncoder.encode("dev-001", "secret-001", nextHour);

        assertThat(at0200.clientId()).isEqualTo("dev-001_0_0_2025041402");
        assertThat(at0200.password()).as("时间戳参与 HMAC 运算，跨小时签名必变").isNotEqualTo(at0100.password());
        String timestampSegment = at0100.clientId().substring(at0100.clientId().lastIndexOf('_') + 1);
        assertThat(timestampSegment)
                .as("时间戳段 = UTC yyyyMMddHH 10 位小时粒度（官方格式，非毫秒）")
                .matches("\\d{10}");
    }
}

package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.StatusFrame;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.TelemetryFrame;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 遥测帧解析器单元测试（BRIEF-PR4-01 §3 单测清单：遥测帧/状态帧/非数值 BAD/缺字段毒丸/非 JSON 毒丸）。
 *
 * <p>覆盖 CF-7 遥测形态与状态形态双分支、缺省字段契约默认值（quality=GOOD/source=IOTDA）、
 * value 非数值标 BAD 保留原文、时间多形态解析（Z/时区偏移）与全部毒丸拒绝路径。
 * 解析器为 static 纯函数，测试直调无夹具装配。
 */
class TelemetryFrameParserTest {

    // ---------------------------------------------------------------- 遥测帧分支

    @Test
    @DisplayName("合法遥测帧（全字段）：映射为 CF-7 标准消息且七字段逐一对应")
    void telemetryFrameWithAllFieldsMapsToStandardMessage() {
        byte[] raw = """
                {"deviceId":"it-dev-001","metricCode":"MDC_ECG_HEART_RATE","value":"72",
                 "unit":"bpm","occurredAt":"2026-09-10T04:00:00Z","quality":"SUSPECT","source":"IOTDA"}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(frame).isInstanceOf(TelemetryFrame.class);
        StandardTelemetryMessage message = ((TelemetryFrame) frame).message();
        assertThat(message.deviceId()).isEqualTo("it-dev-001");
        assertThat(message.metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(message.value()).isEqualTo("72");
        assertThat(message.unit()).isEqualTo("bpm");
        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
        assertThat(message.quality()).isEqualTo("SUSPECT");
        assertThat(message.source()).isEqualTo("IOTDA");
    }

    @Test
    @DisplayName("遥测帧缺省可选字段：unit 为空、quality 默认 GOOD、source 默认 IOTDA")
    void telemetryFrameWithoutOptionalFieldsAppliesContractDefaults() {
        byte[] raw = """
                {"deviceId":"dev-02","metricCode":"MDC_SPO2","value":"98.5","occurredAt":"2026-09-10T04:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        StandardTelemetryMessage message = ((TelemetryFrame) TelemetryFrameParser.parse(raw)).message();

        assertThat(message.unit()).isNull();
        assertThat(message.quality()).isEqualTo("GOOD");
        assertThat(message.source()).isEqualTo("IOTDA");
    }

    @Test
    @DisplayName("value 非数值：quality 强制 BAD 且原文保留入库不阻断（标注不丢弃口径）")
    void nonNumericValueIsMarkedBadWithOriginalTextKept() {
        byte[] raw = """
                {"deviceId":"dev-03","metricCode":"MDC_ECG_HEART_RATE","value":"N/A","occurredAt":"2026-09-10T04:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        StandardTelemetryMessage message = ((TelemetryFrame) TelemetryFrameParser.parse(raw)).message();

        assertThat(message.value()).isEqualTo("N/A");
        assertThat(message.quality()).isEqualTo("BAD");
    }

    @Test
    @DisplayName("value 为 JSON 数值型（非字符串）：按文本承接且解析为 GOOD")
    void numericJsonValueIsAcceptedAsGood() {
        byte[] raw = """
                {"deviceId":"dev-04","metricCode":"MDC_RESP_RATE","value":16,"occurredAt":"2026-09-10T04:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        StandardTelemetryMessage message = ((TelemetryFrame) TelemetryFrameParser.parse(raw)).message();

        assertThat(message.value()).isEqualTo("16");
        assertThat(message.quality()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("occurredAt 带时区偏移（+08:00）：正确换算为 UTC Instant")
    void occurredAtWithZoneOffsetIsConvertedToUtcInstant() {
        byte[] raw = """
                {"deviceId":"dev-05","metricCode":"MDC_BODY_TEMP","value":"36.8","occurredAt":"2026-09-10T12:00:00+08:00"}
                """.getBytes(StandardCharsets.UTF_8);

        StandardTelemetryMessage message = ((TelemetryFrame) TelemetryFrameParser.parse(raw)).message();

        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
    }

    // ---------------------------------------------------------------- 状态帧分支

    @Test
    @DisplayName("合法状态帧：映射为设备状态事件（status 枚举化、wardId 按 P0 契约为 null）")
    void statusFrameMapsToDeviceStatusEvent() {
        byte[] raw = """
                {"deviceId":"it-dev-001","status":"OFFLINE","occurredAt":"2026-09-10T05:30:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(frame).isInstanceOf(StatusFrame.class);
        DeviceStatusEvent event = ((StatusFrame) frame).event();
        assertThat(event.deviceId()).isEqualTo("it-dev-001");
        assertThat(event.status()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-10T05:30:00Z"));
        assertThat(event.wardId()).isNull();
    }

    // ---------------------------------------------------------------- 毒丸拒绝路径

    @Test
    @DisplayName("非 JSON 报文：FrameParseException 毒丸（交调用方落错误日志后确认抛弃）")
    void nonJsonPayloadIsRejectedAsPoison() {
        byte[] raw = "not-a-json-frame".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("遥测帧缺 occurredAt：形态判别失败抛毒丸（CF-7 必填字段缺失即失败）")
    void telemetryFrameMissingOccurredAtIsRejected() {
        byte[] raw = """
                {"deviceId":"dev-06","metricCode":"MDC_ECG_HEART_RATE","value":"72"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("形态判别失败");
    }

    @Test
    @DisplayName("occurredAt 时间格式不可解析：抛毒丸且消息点名字段")
    void unparseableOccurredAtIsRejected() {
        byte[] raw = """
                {"deviceId":"dev-07","metricCode":"MDC_BODY_TEMP","value":"36.8","occurredAt":"yesterday"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("状态帧 status 非法值：值域越界抛毒丸（脏状态不得静默透传）")
    void statusFrameWithIllegalStatusIsRejected() {
        byte[] raw = """
                {"deviceId":"dev-08","status":"POWERED_BY_MAGIC","occurredAt":"2026-09-10T05:30:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw)).isInstanceOf(FrameParseException.class);
    }

    @Test
    @DisplayName("遥测帧 quality 非法值：值域越界抛毒丸（不得静默降级为默认质量）")
    void telemetryFrameWithIllegalQualityIsRejected() {
        byte[] raw = """
                {"deviceId":"dev-09","metricCode":"MDC_SPO2","value":"98","quality":"PERFECT","occurredAt":"2026-09-10T04:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw)).isInstanceOf(FrameParseException.class);
    }

    @Test
    @DisplayName("空 JSON 对象：既非遥测形态也非状态形态，抛毒丸")
    void emptyObjectIsRejectedAsUnrecognizedShape() {
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("形态判别失败");
    }
}

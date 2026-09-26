package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.StatusFrame;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.TelemetryBatchFrame;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.TelemetryFrame;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
    @DisplayName("value 非数值：quality 强制 BAD 且原文保留于解析产物、落库跳过不阻断（标注不丢弃口径）")
    void nonNumericValueIsMarkedBadWithOriginalTextKept() {
        byte[] raw = """
                {"deviceId":"dev-03","metricCode":"MDC_ECG_HEART_RATE","value":"N/A","occurredAt":"2026-09-10T04:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);

        StandardTelemetryMessage message = ((TelemetryFrame) TelemetryFrameParser.parse(raw)).message();

        // 解析器仅负责标注与保留原文；落库侧由 TelemetryIngestServiceImpl 对非数值行跳过不入库（不阻断批次）
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

    // ------------------------------------------------- IoTDA 推送帧分支（L-3 冻结映射，TASK.md L-3）

    @Test
    @DisplayName("真实 IoTDA 取证报文：展开为 2 条 CF-7 标准遥测消息且逐字段对齐（L-3 冻结映射）")
    void realIotdaPushPayloadExpandsToTwoStandardTelemetryMessages() {
        // raw_payload 取证原文（iot_consume_error_log 569 帧同构样例），字段内容逐字原样用作测试样例
        byte[] raw = """
                {"resource":"device.property","event":"report","event_time_ms":"2026-09-12T17:30:41.632Z",
                 "notify_data":{"header":{"device_id":"6aa570ac155456566827c784_fuyun-demo-001",
                 "node_id":"fuyun-demo-001","product_id":"6aa570ac155456566827c784"},
                 "body":{"services":[{"service_id":"Monitor","properties":{"heartRate":78,"spo2":100},
                 "event_time":"20260912T173041Z"}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(frame).isInstanceOf(TelemetryBatchFrame.class);
        List<StandardTelemetryMessage> messages = ((TelemetryBatchFrame) frame).messages();
        assertThat(messages).as("properties 两个属性键各展开一条，共 2 条").hasSize(2);
        // 顺序与 services[].properties 键序一致：heartRate 在前、spo2 在后
        StandardTelemetryMessage heartRate = messages.get(0);
        assertThat(heartRate.deviceId()).isEqualTo("6aa570ac155456566827c784_fuyun-demo-001");
        assertThat(heartRate.metricCode()).as("metricCode = 属性名（P1 建字典再规范化）").isEqualTo("heartRate");
        assertThat(heartRate.value())
                .as("数值标量以文本承载（StandardTelemetryMessage 契约）")
                .isEqualTo("78");
        assertThat(heartRate.unit()).as("IoTDA 属性上报不含单位，unit 恒 null").isNull();
        assertThat(heartRate.occurredAt())
                .as("occurredAt 取顶层 event_time_ms 解析为 UTC Instant")
                .isEqualTo(Instant.parse("2026-09-12T17:30:41.632Z"));
        assertThat(heartRate.quality()).as("数值属性按既有 isNumeric 口径落 GOOD").isEqualTo("GOOD");
        assertThat(heartRate.source()).isEqualTo("IOTDA");
        StandardTelemetryMessage spo2 = messages.get(1);
        assertThat(spo2.deviceId()).isEqualTo("6aa570ac155456566827c784_fuyun-demo-001");
        assertThat(spo2.metricCode()).isEqualTo("spo2");
        assertThat(spo2.value()).isEqualTo("100");
        assertThat(spo2.unit()).isNull();
        assertThat(spo2.occurredAt()).isEqualTo(Instant.parse("2026-09-12T17:30:41.632Z"));
        assertThat(spo2.quality()).isEqualTo("GOOD");
        assertThat(spo2.source()).isEqualTo("IOTDA");
    }

    @Test
    @DisplayName("多服务多属性：按 services 数组序与 properties 键序展开，条数与顺序一致")
    void multiServiceMultiPropertyExpandsInDeclarationOrder() {
        byte[] raw = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"ward-dev-009"},"body":{"services":[
                 {"service_id":"Monitor","properties":{"heartRate":88,"spo2":97,"respRate":18}},
                 {"service_id":"Lab","properties":{"glucose":5.6,"bodyTemp":36.8}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        List<StandardTelemetryMessage> messages = ((TelemetryBatchFrame) TelemetryFrameParser.parse(raw)).messages();

        assertThat(messages).as("两个服务五个属性键共展开 5 条").hasSize(5);
        assertThat(messages)
                .extracting(StandardTelemetryMessage::metricCode)
                .containsExactly("heartRate", "spo2", "respRate", "glucose", "bodyTemp");
        assertThat(messages).extracting(StandardTelemetryMessage::deviceId).containsOnly("ward-dev-009");
    }

    @Test
    @DisplayName("属性值承载分型：字符串数值 GOOD；非数值文本/布尔/null/对象/数组标 BAD 且原文承载")
    void propertyValueCarryingFollowsScalarTypingRules() {
        byte[] raw = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-carry"},"body":{"services":[
                 {"service_id":"Monitor","properties":{"steps":"4200","note":"N/A","fallDetected":true,
                 "signal":null,"waveform":{"lead":"II"},"samples":[1,2,3]}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        List<StandardTelemetryMessage> messages = ((TelemetryBatchFrame) TelemetryFrameParser.parse(raw)).messages();

        assertThat(messages).hasSize(6);
        StandardTelemetryMessage steps = messages.get(0);
        assertThat(steps.value()).as("字符串型数值属性解析为 GOOD").isEqualTo("4200");
        assertThat(steps.quality()).isEqualTo("GOOD");
        StandardTelemetryMessage note = messages.get(1);
        assertThat(note.value()).as("非数值文本原文保留（标注不阻断口径）").isEqualTo("N/A");
        assertThat(note.quality()).isEqualTo("BAD");
        StandardTelemetryMessage fallDetected = messages.get(2);
        assertThat(fallDetected.value()).as("布尔属性转文本承载").isEqualTo("true");
        assertThat(fallDetected.quality()).as("布尔不可数值定型标 BAD").isEqualTo("BAD");
        StandardTelemetryMessage signal = messages.get(3);
        assertThat(signal.value()).as("null 字面量以文本 \"null\" 承载").isEqualTo("null");
        assertThat(signal.quality()).isEqualTo("BAD");
        StandardTelemetryMessage waveform = messages.get(4);
        assertThat(waveform.value()).as("对象属性以紧凑 JSON 文本承载").isEqualTo("{\"lead\":\"II\"}");
        assertThat(waveform.quality()).isEqualTo("BAD");
        StandardTelemetryMessage samples = messages.get(5);
        assertThat(samples.value()).as("数组属性以紧凑 JSON 文本承载").isEqualTo("[1,2,3]");
        assertThat(samples.quality()).isEqualTo("BAD");
    }

    @Test
    @DisplayName("IoTDA 推送帧缺 device_id：毒丸拒绝（notify_data.header.device_id 必填）")
    void iotdaPushWithoutDeviceIdIsRejected() {
        byte[] raw = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"node_id":"n-1"},"body":{"services":[
                 {"service_id":"Monitor","properties":{"heartRate":78}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("device_id");
    }

    @Test
    @DisplayName("IoTDA 推送帧 device_id 为空白文本：毒丸拒绝（非空白文本契约）")
    void iotdaPushWithBlankDeviceIdIsRejected() {
        byte[] raw = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"   "},"body":{"services":[
                 {"service_id":"Monitor","properties":{"heartRate":78}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("device_id");
    }

    @Test
    @DisplayName("IoTDA 推送帧 event_time_ms 缺失或不可解析：毒丸拒绝")
    void iotdaPushWithMissingOrUnparseableEventTimeIsRejected() {
        byte[] missing = """
                {"resource":"device.property",
                 "notify_data":{"header":{"device_id":"dev-t1"},"body":{"services":[
                 {"service_id":"Monitor","properties":{"heartRate":78}}]}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] unparseable = """
                {"resource":"device.property","event_time_ms":"yesterday",
                 "notify_data":{"header":{"device_id":"dev-t1"},"body":{"services":[
                 {"service_id":"Monitor","properties":{"heartRate":78}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(missing))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("event_time_ms");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(unparseable))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("event_time_ms");
    }

    @Test
    @DisplayName("IoTDA 推送帧 services 缺失/非数组/空数组：毒丸拒绝")
    void iotdaPushWithInvalidServicesIsRejected() {
        byte[] missing = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-s1"},"body":{}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] notArray = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-s1"},
                 "body":{"services":{"service_id":"Monitor"}}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] emptyArray = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-s1"},"body":{"services":[]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(missing))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("services");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(notArray))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("services");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(emptyArray))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("services");
    }

    @Test
    @DisplayName("IoTDA 推送帧 service 缺 properties 或 properties 为空对象：毒丸拒绝（无可消费遥测属性）")
    void iotdaPushWithInvalidPropertiesIsRejected() {
        byte[] missingProperties = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-p1"},"body":{"services":[
                 {"service_id":"Monitor"}]}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] emptyProperties = """
                {"resource":"device.property","event_time_ms":"2026-09-12T18:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-p1"},"body":{"services":[
                 {"service_id":"Monitor","properties":{}}]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(missingProperties))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("properties");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(emptyProperties))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("properties");
    }

    @Test
    @DisplayName("resource 非 device.property：回退既有遥测/状态判别，均不匹配抛既有判别失败文案")
    void nonDevicePropertyResourceFallsBackToCf7Discrimination() {
        byte[] raw = """
                {"resource":"device.status","deviceId":"dev-10"}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("形态判别失败");
    }

    // ------------------------------------------------- 设备告警帧分支（Task 7 透传规则源）

    @Test
    @DisplayName("resource=device.alarm：判为第四形态并按 IoTDA 文档结构解析（告警名/描述/时刻）")
    void deviceAlarmFrameParsesWithDocumentedShape() {
        byte[] raw = """
                {"resource":"device.alarm","event":"alarm","event_time_ms":"2026-09-26T08:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-a1"},
                 "body":{"alarm_id":"al-9","name":"deviceAlarmEvent","severity":"MAJOR",
                 "description":"设备自检告警"}}}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(frame).isInstanceOf(ParsedFrame.DeviceAlarmFrame.class);
        ParsedFrame.DeviceAlarmFrame alarmFrame = (ParsedFrame.DeviceAlarmFrame) frame;
        assertThat(alarmFrame.deviceId()).isEqualTo("dev-a1");
        assertThat(alarmFrame.metricCode()).isEqualTo("deviceAlarmEvent");
        assertThat(alarmFrame.severity()).isEqualTo("MAJOR");
        assertThat(alarmFrame.description()).isEqualTo("设备自检告警");
        assertThat(alarmFrame.occurredAt()).isEqualTo(Instant.parse("2026-09-26T08:00:00Z"));
    }

    @Test
    @DisplayName("device.alarm 帧告警名缺失：回退 alarm_id 承载 metricCode")
    void deviceAlarmFrameFallsBackToAlarmId() {
        byte[] raw = """
                {"resource":"device.alarm","event_time_ms":"2026-09-26T08:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-a1"},
                 "body":{"alarm_id":"al-9","severity":"MAJOR"}}}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(((ParsedFrame.DeviceAlarmFrame) frame).metricCode()).isEqualTo("al-9");
    }

    @Test
    @DisplayName("device.alarm 帧告警名与标识均缺失：毒丸拒绝（不可归因报文不透传）")
    void deviceAlarmFrameWithoutNameAndIdIsPoison() {
        byte[] raw = """
                {"resource":"device.alarm","event_time_ms":"2026-09-26T08:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-a1"},"body":{"severity":"MAJOR"}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("name/alarm_id");
    }

    @Test
    @DisplayName("device.alarm 帧 device_id 缺失或 body 非对象：毒丸拒绝")
    void deviceAlarmFrameStructuralViolationsArePoison() {
        byte[] missingDevice = """
                {"resource":"device.alarm","event_time_ms":"2026-09-26T08:00:00Z",
                 "notify_data":{"header":{},
                 "body":{"name":"deviceAlarmEvent"}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] missingBody = """
                {"resource":"device.alarm","event_time_ms":"2026-09-26T08:00:00Z",
                 "notify_data":{"header":{"device_id":"dev-a1"}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(missingDevice))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("device_id");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(missingBody))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("body");
    }

    // ---------------------------------------------------------------- 命令状态帧分支（第五形态，Task 8）

    @Test
    @DisplayName("resource=device.command.status：判为第五形态并按 IoTDA 文档结构解析（设备/命令/状态/摘要/时刻）")
    void commandStatusFrameMapsToCommandResultFrame() {
        byte[] raw = """
                {"resource":"device.command.status","event":"command.status.update","event_time_ms":"2026-09-26T01:02:03.456Z",
                 "notify_data":{"header":{"device_id":"dev-cmd-01","product_id":"prod-01"},
                 "body":{"command_id":"sim-cmd-2","status":"SUCCESS","result":{"resultCode":0}}}}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame frame = TelemetryFrameParser.parse(raw);

        assertThat(frame).isInstanceOf(ParsedFrame.CommandResultFrame.class);
        ParsedFrame.CommandResultFrame commandFrame = (ParsedFrame.CommandResultFrame) frame;
        assertThat(commandFrame.deviceId()).isEqualTo("dev-cmd-01");
        assertThat(commandFrame.commandId()).isEqualTo("sim-cmd-2");
        assertThat(commandFrame.registryStatus()).isEqualTo("SUCCESS");
        // 对象形态结果摘要以紧凑 JSON 文本承载留证（value 承载同构口径）
        assertThat(commandFrame.result()).isEqualTo("{\"resultCode\":0}");
        assertThat(commandFrame.occurredAt()).isEqualTo(Instant.parse("2026-09-26T01:02:03.456Z"));
    }

    @Test
    @DisplayName("命令状态帧 result 缺省：摘要为 null（成功终态无失败原因语义）")
    void commandStatusFrameWithoutResultKeepsNullSummary() {
        byte[] raw = """
                {"resource":"device.command.status","event_time_ms":"2026-09-26T01:02:03Z",
                 "notify_data":{"header":{"device_id":"dev-cmd-01"},
                 "body":{"command_id":"sim-cmd-2","status":"DELIVERED"}}}
                """.getBytes(StandardCharsets.UTF_8);

        ParsedFrame.CommandResultFrame commandFrame = (ParsedFrame.CommandResultFrame) TelemetryFrameParser.parse(raw);

        assertThat(commandFrame.registryStatus()).isEqualTo("DELIVERED");
        assertThat(commandFrame.result()).isNull();
    }

    @Test
    @DisplayName("命令状态帧 status 值域越界：毒丸拒绝（命令状态机不接收不可归类状态）")
    void commandStatusFrameWithUnknownStatusIsPoison() {
        byte[] raw = """
                {"resource":"device.command.status","event_time_ms":"2026-09-26T01:02:03Z",
                 "notify_data":{"header":{"device_id":"dev-cmd-01"},
                 "body":{"command_id":"sim-cmd-2","status":"WHAT_IS_THIS"}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(raw))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("status 值域越界");
    }

    @Test
    @DisplayName("命令状态帧 command_id 缺失或 device_id 空白：毒丸拒绝（结构契约必填）")
    void commandStatusFrameStructuralViolationsArePoison() {
        byte[] missingCommandId = """
                {"resource":"device.command.status","event_time_ms":"2026-09-26T01:02:03Z",
                 "notify_data":{"header":{"device_id":"dev-cmd-01"},
                 "body":{"status":"SUCCESS"}}}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] missingDevice = """
                {"resource":"device.command.status","event_time_ms":"2026-09-26T01:02:03Z",
                 "notify_data":{"header":{},
                 "body":{"command_id":"sim-cmd-2","status":"SUCCESS"}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TelemetryFrameParser.parse(missingCommandId))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("command_id");
        assertThatThrownBy(() -> TelemetryFrameParser.parse(missingDevice))
                .isInstanceOf(FrameParseException.class)
                .hasMessageContaining("device_id");
    }
}

package com.fuyun.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 遥测帧解析器：AMQP 消费四路同构的"P0 同构"落点（BRIEF-PR4-01 §1.3/§3，宪法 A.5-9 链路）。
 *
 * <p>线格式契约：P0 以 CF-7 JSON 为线格式；真实 IoTDA 规则引擎推送报文映射已随 TASK.md L-3 冻结
 * （用户 2026-09-13 批准选项 B 代码映射），真实报文在解析器内展开为 N 条 CF-7 标准遥测消息，
 * 下游攒批/落库/推送管道零改动。
 *
 * <p>形态判别（三形态，按序优先）：①顶层 {@code resource} 精确等于 {@code "device.property"} 判为
 * IoTDA 推送形态——deviceId ← {@code notify_data.header.device_id}（必填非空白），occurredAt ← 顶层
 * {@code event_time_ms}（ISO-8601 解析为 UTC Instant），{@code notify_data.body.services[]} 每元素的
 * {@code properties} 键值各展开一条标准消息（metricCode=属性名，value 字符串承载，unit 恒 null，
 * quality 按数值可定型与否 GOOD/BAD 标注不阻断，source=IOTDA）；②含 deviceId+metricCode+value+
 * occurredAt 判为 CF-7 遥测帧（→ {@link StandardTelemetryMessage}）；③含 deviceId+status+
 * occurredAt 判为状态帧（→ {@link DeviceStatusEvent}）。非 IoTDA 形态回退②③既有判别（CF-7 帧不
 * 含 resource 字段，判别前移零冲突）；均不匹配抛 {@link FrameParseException}（毒丸，交调用方落
 * iot_consume_error_log 后确认抛弃）。遥测帧 value 非数值时 quality 强制 BAD 并保留原文（标注不
 * 阻断口径）；occurredAt/event_time_ms 缺失或不可解析按解析失败处置。
 *
 * <p>static 纯函数式、无状态（backend 宪法 A.1-9 服务无状态多实例前提）；ObjectMapper 线程安全
 * 可静态复用。归 internal/ 包：容器驱动链路的模块内组件，禁止外部引用（宪法 B.1）。
 */
public final class TelemetryFrameParser {

    /** JSON 解析器：ObjectMapper 线程安全，static 单例复用（纯函数式解析无配置定制） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 私有构造器：纯函数工具类禁止实例化。
     */
    private TelemetryFrameParser() {}

    /**
     * 解析帧的三形态结果（sealed 受限子类型，宪法 A.1-3 编译器穷尽检查——消费侧 switch
     * 仅需处理遥测/批量遥测/状态三分支，新增形态编译期强制补齐）。
     */
    public sealed interface ParsedFrame {

        /**
         * 遥测帧解析产物。
         *
         * @param message CF-7 标准遥测消息，非空
         */
        record TelemetryFrame(StandardTelemetryMessage message) implements ParsedFrame {}

        /**
         * IoTDA 推送帧展开产物：真实报文按 L-3 冻结映射展开为 N 条 CF-7 标准遥测消息，下游按
         * 多条普通遥测消费（攒批/落库/推送管道零改动）。
         *
         * @param messages 展开消息列表，非空且 ≥1 条（解析器保证：services ≥1 且每个 properties
         *                 为非空对象，否则解析已按毒丸拒绝，空列表不可能产出）
         */
        record TelemetryBatchFrame(List<StandardTelemetryMessage> messages) implements ParsedFrame {}

        /**
         * 状态帧解析产物。
         *
         * @param event 设备状态变更事件，非空
         */
        record StatusFrame(DeviceStatusEvent event) implements ParsedFrame {}
    }

    /**
     * 解析一帧原始报文为 IoTDA 推送帧（展开）、遥测帧或状态帧。
     *
     * @param raw 帧原始字节（消费侧 receive 原文），非空；UTF-8 解码
     * @return 解析产物：IoTDA 推送展开帧、遥测帧或状态帧（sealed 三分支），非空
     * @throws FrameParseException 非 JSON 报文、形态判别失败（缺必填字段）、字段值域越界
     *                             （quality/source/status 非法值）或时间字段不可解析；
     *                             调用方应落错误日志后确认抛弃（毒丸隔离），禁无限重投
     */
    public static ParsedFrame parse(byte[] raw) {
        JsonNode root;
        String text = new String(raw, StandardCharsets.UTF_8);
        try {
            root = MAPPER.readTree(text);
        } catch (Exception e) {
            // 非 JSON 毒丸：Jackson 多态异常统一收口为 FrameParseException，原因不含帧原文
            throw new FrameParseException("帧不是合法 JSON 报文", e);
        }
        if (!root.isObject()) {
            throw new FrameParseException("帧顶层必须是 JSON 对象");
        }
        // IoTDA 推送形态优先判别（顶层 resource 精确匹配，L-3 冻结）；非该形态回退 CF-7 既有判别
        if (isIotdaPushShape(root)) {
            return parseIotdaDeviceProperty(root);
        }
        if (isTelemetryShape(root)) {
            return new ParsedFrame.TelemetryFrame(parseTelemetry(root));
        }
        if (isStatusShape(root)) {
            return parseStatus(root);
        }
        throw new FrameParseException(
                "帧形态判别失败：既非遥测形态（deviceId/metricCode/value/occurredAt）也非状态形态（deviceId/status/occurredAt）");
    }

    /**
     * IoTDA 推送形态判别：顶层 resource 字段存在且精确等于 "device.property"（区分大小写，
     * 非该值一律回退既有 CF-7 判别，行为零变化）。
     *
     * @param root 帧根节点，非空且为对象
     * @return true=按 IoTDA 推送帧解析展开
     */
    private static boolean isIotdaPushShape(JsonNode root) {
        JsonNode resource = root.path(IotMessagingConstants.FRAME_FIELD_RESOURCE);
        return !resource.isMissingNode()
                && IotMessagingConstants.IOTDA_RESOURCE_DEVICE_PROPERTY.equals(resource.asText());
    }

    /**
     * 遥测形态判别：四必填字段（deviceId/metricCode/value/occurredAt）全部存在且非空标量。
     *
     * @param root 帧根节点，非空且为对象
     * @return true=按遥测帧解析
     */
    private static boolean isTelemetryShape(JsonNode root) {
        return hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_DEVICE_ID)
                && hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_METRIC_CODE)
                && hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_VALUE)
                && hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_OCCURRED_AT);
    }

    /**
     * 状态形态判别：三必填字段（deviceId/status/occurredAt）全部存在且非空文本。
     *
     * @param root 帧根节点，非空且为对象
     * @return true=按状态帧解析
     */
    private static boolean isStatusShape(JsonNode root) {
        return hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_DEVICE_ID)
                && hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_STATUS)
                && hasNonBlankScalar(root, IotMessagingConstants.FRAME_FIELD_OCCURRED_AT);
    }

    /**
     * 解析遥测帧为 CF-7 标准消息（七字段映射，缺省字段按契约默认值补齐）。
     *
     * @param root 帧根节点（已判别为遥测形态），非空
     * @return 标准遥测消息，非空
     * @throws FrameParseException occurredAt 不可解析、quality/source 值域越界
     */
    private static StandardTelemetryMessage parseTelemetry(JsonNode root) {
        String valueText = root.path(IotMessagingConstants.FRAME_FIELD_VALUE).asText();
        // value 非数值 → quality 强制 BAD 并保留原文（标注不阻断，14-iot FU-M14-05 数据质量口径）
        TelemetryQuality quality = isNumeric(valueText) ? parseQualityOrDefault(root) : TelemetryQuality.BAD;
        return new StandardTelemetryMessage(
                root.path(IotMessagingConstants.FRAME_FIELD_DEVICE_ID).asText(),
                root.path(IotMessagingConstants.FRAME_FIELD_METRIC_CODE).asText(),
                valueText,
                textOrNull(root, IotMessagingConstants.FRAME_FIELD_UNIT),
                parseOccurredAt(root),
                quality.getCode(),
                parseSourceOrDefault(root).getCode());
    }

    /**
     * 解析 IoTDA 推送帧为展开遥测批（L-3 冻结映射）：
     * deviceId ← notify_data.header.device_id（必填非空白）；occurredAt ← 顶层 event_time_ms；
     * notify_data.body.services[] 每元素 properties 键值各展开一条标准消息——metricCode=属性名，
     * value 字符串承载（标量 asText，对象/数组紧凑 JSON 文本），unit 恒 null（IoTDA 属性上报
     * 不含单位），quality 按数值可定型口径（GOOD/BAD 标注不阻断，与 CF-7 value 同构），source=IOTDA。
     *
     * <p>任一结构契约不满足（deviceId 缺失/空白、时间缺失/不可解析、services 缺失/非数组/空、
     * properties 缺失/非对象/空对象）即抛毒丸统一收口，不产出部分展开结果。
     *
     * @param root 帧根节点（已判别为 IoTDA 推送形态），非空
     * @return 展开遥测批，非空且 ≥1 条（services ≥1 且每个 properties 非空保证）
     * @throws FrameParseException 结构契约任一不满足（毒丸）
     */
    private static ParsedFrame.TelemetryBatchFrame parseIotdaDeviceProperty(JsonNode root) {
        JsonNode header =
                root.path(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA).path(IotMessagingConstants.IOTDA_FIELD_HEADER);
        if (!hasNonBlankScalar(header, IotMessagingConstants.IOTDA_FIELD_DEVICE_ID)) {
            throw new FrameParseException("IoTDA 推送帧 device_id 缺失或空白（notify_data.header.device_id 必填）");
        }
        String deviceId =
                header.path(IotMessagingConstants.IOTDA_FIELD_DEVICE_ID).asText();
        Instant occurredAt = parseInstantText(
                root.path(IotMessagingConstants.IOTDA_FIELD_EVENT_TIME_MS).asText(),
                IotMessagingConstants.IOTDA_FIELD_EVENT_TIME_MS);
        JsonNode services = root.path(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA)
                .path(IotMessagingConstants.IOTDA_FIELD_BODY)
                .path(IotMessagingConstants.IOTDA_FIELD_SERVICES);
        if (!services.isArray() || services.size() < 1) {
            throw new FrameParseException("IoTDA 推送帧 services 缺失/非数组或为空（notify_data.body.services 至少 1 个服务）");
        }
        List<StandardTelemetryMessage> messages = new ArrayList<>();
        for (JsonNode service : services) {
            appendServiceProperties(deviceId, occurredAt, service, messages);
        }
        return new ParsedFrame.TelemetryBatchFrame(messages);
    }

    /**
     * 展开单个 service 的 properties 属性表为标准遥测消息并追加至批列表（顺序 = 属性键声明序）。
     *
     * @param deviceId   设备号（notify_data.header.device_id，前置校验已通过），非空
     * @param occurredAt 发生时刻（顶层 event_time_ms 解析产物），非空
     * @param service    服务节点，非空；其 properties 必须为非空对象
     * @param messages   展开消息收集列表，非空；本方法按序追加
     * @throws FrameParseException properties 缺失/非对象或空对象（无可消费遥测属性，毒丸）
     */
    private static void appendServiceProperties(
            String deviceId, Instant occurredAt, JsonNode service, List<StandardTelemetryMessage> messages) {
        JsonNode properties = service.path(IotMessagingConstants.IOTDA_FIELD_PROPERTIES);
        if (!properties.isObject() || properties.isEmpty()) {
            throw new FrameParseException("IoTDA 推送帧 service properties 缺失/非对象或为空对象（无可消费遥测属性）");
        }
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            JsonNode valueNode = property.getValue();
            // 值承载：JSON 标量（文本/数值/布尔/null 字面量）统一 asText 文本；对象/数组以紧凑
            // JSON 文本承载留证——两者同走 isNumeric 质量口径（数值 GOOD，其余 BAD 标注不阻断）
            String valueText = valueNode.isValueNode() ? valueNode.asText() : valueNode.toString();
            TelemetryQuality quality = isNumeric(valueText) ? TelemetryQuality.GOOD : TelemetryQuality.BAD;
            messages.add(new StandardTelemetryMessage(
                    deviceId,
                    property.getKey(),
                    valueText,
                    null,
                    occurredAt,
                    quality.getCode(),
                    TelemetrySource.IOTDA.getCode()));
        }
    }

    /**
     * 解析状态帧为设备状态变更事件（P0 占位载荷：wardId 状态帧契约不含，恒 null——由消费者
     * 以设备档案 ward_id 补全后才发布事件）。
     *
     * @param root 帧根节点（已判别为状态形态），非空
     * @return 设备状态变更事件，非空
     * @throws FrameParseException status 值域越界或 occurredAt 不可解析
     */
    private static ParsedFrame.StatusFrame parseStatus(JsonNode root) {
        String statusCode = root.path(IotMessagingConstants.FRAME_FIELD_STATUS).asText();
        // status 值域校验（∈ DeviceStatus 枚举）：非法值属解析失败（毒丸统一收口），不得静默透传脏状态
        DeviceStatus status;
        try {
            status = DeviceStatus.fromCode(statusCode);
        } catch (IllegalArgumentException e) {
            throw new FrameParseException("status 值域越界（非设备状态枚举合法值）", e);
        }
        return new ParsedFrame.StatusFrame(new DeviceStatusEvent(
                root.path(IotMessagingConstants.FRAME_FIELD_DEVICE_ID).asText(), status, parseOccurredAt(root), null));
    }

    /**
     * 解析 occurredAt 为 UTC Instant：优先带时区偏移的 OffsetDateTime（兼容 Z/±hh:mm），
     * 回退 ISO-8601 Instant 串。
     *
     * @param root 帧根节点，非空
     * @return 发生时刻（UTC），非空
     * @throws FrameParseException 字段缺失或时间格式不可解析（CF-7 契约必填项，缺失即毒丸）
     */
    private static Instant parseOccurredAt(JsonNode root) {
        return parseInstantText(
                root.path(IotMessagingConstants.FRAME_FIELD_OCCURRED_AT).asText(), "occurredAt");
    }

    /**
     * 按文本解析发生时刻为 UTC Instant（CF-7 occurredAt 与 IoTDA event_time_ms 共用）：
     * 优先带时区偏移的 OffsetDateTime（兼容 Z/±hh:mm），回退 ISO-8601 Instant 串。
     *
     * @param text      时刻文本，非空（可为空串——字段缺失时 path().asText() 产物，解析必失败）
     * @param fieldName 字段名（毒丸文案点名，CF-7 为 occurredAt / IoTDA 为 event_time_ms），非空
     * @return 发生时刻（UTC），非空
     * @throws FrameParseException 时间文本缺失或格式不可解析（毒丸统一收口）
     */
    private static Instant parseInstantText(String text, String fieldName) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // 带偏移解析失败回退 ISO-8601 Instant 形态（如 2026-09-10T00:00:00Z 之外的紧凑形态）
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new FrameParseException(fieldName + " 缺失或不可解析（要求 ISO-8601 时间）", e);
        }
    }

    /**
     * 解析帧内 quality 字段，缺省 GOOD（CF-7 契约默认值）。
     *
     * @param root 帧根节点，非空
     * @return 质量枚举，非空
     * @throws FrameParseException quality 值域越界（非法值属毒丸，不得静默降级）
     */
    private static TelemetryQuality parseQualityOrDefault(JsonNode root) {
        String quality = textOrNull(root, IotMessagingConstants.FRAME_FIELD_QUALITY);
        if (quality == null) {
            return TelemetryQuality.GOOD;
        }
        try {
            return TelemetryQuality.fromCode(quality);
        } catch (IllegalArgumentException e) {
            // 值域越界属毒丸（统一收口为 FrameParseException），不得静默降级为默认质量
            throw new FrameParseException("quality 值域越界（要求 GOOD/SUSPECT/BAD）", e);
        }
    }

    /**
     * 解析帧内 source 字段，缺省 IOTDA（P0 主链路唯一来源，HL7 随模式 D 实装启用）。
     *
     * @param root 帧根节点，非空
     * @return 来源枚举，非空
     * @throws FrameParseException source 值域越界（非法值属毒丸，不得静默降级）
     */
    private static TelemetrySource parseSourceOrDefault(JsonNode root) {
        String source = textOrNull(root, IotMessagingConstants.FRAME_FIELD_SOURCE);
        if (source == null) {
            return TelemetrySource.IOTDA;
        }
        try {
            return TelemetrySource.fromCode(source);
        } catch (IllegalArgumentException e) {
            // 值域越界属毒丸（统一收口为 FrameParseException），不得静默降级为默认来源
            throw new FrameParseException("source 值域越界（要求 IOTDA/HL7）", e);
        }
    }

    /**
     * 判断字段存在且为非空标量（字符串/数值/布尔可转文本；对象/数组/缺失/空串均视为不存在，
     * 供形态判别——value 兼容 IoTDA 直发数值型采集值）。
     *
     * @param root  帧根节点，非空
     * @param field 字段名，非空
     * @return true=字段存在且非空标量
     */
    private static boolean hasNonBlankScalar(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return !node.isMissingNode() && node.isValueNode() && !node.asText().isBlank();
    }

    /**
     * 取可选文本字段：缺失/null/非标量返回 null，标量转文本。
     *
     * @param root  帧根节点，非空
     * @param field 字段名，非空
     * @return 字段文本（空白不裁剪）；缺失或 null 字面量返回 null
     */
    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    /**
     * 判定采集值是否可解析为数值（BigDecimal 兼容整数/小数/科学计数法，NUMERIC 列同域）。
     *
     * @param text 采集值原文，非空
     * @return true=可按数值定型入库
     */
    private static boolean isNumeric(String text) {
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

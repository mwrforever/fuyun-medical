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

/**
 * 遥测帧解析器：AMQP 消费四路同构的"P0 同构"落点（BRIEF-PR4-01 §1.3/§3，宪法 A.5-9 链路）。
 *
 * <p>线格式契约：P0 以 CF-7 JSON 为线格式（真实 IoTDA 规则引擎转发报文的 messageId/properties/
 * services 字段映射随 IOTDA_* 联调批次冻结，P0 不做猜测性兼容）；状态帧 P0 契约形态为
 * {@code {"deviceId","status","occurredAt"}}（14-iot §5 状态机发布点）。
 *
 * <p>形态判别（按字段组合）：含 deviceId+metricCode+value+occurredAt 判为遥测帧（→
 * {@link StandardTelemetryMessage}）；含 deviceId+status+occurredAt 判为状态帧（→
 * {@link DeviceStatusEvent}）；均不匹配抛 {@link FrameParseException}（毒丸，交调用方落
 * iot_consume_error_log 后确认抛弃）。遥测帧 value 非数值时 quality 强制 BAD 并保留原文
 * （标注不阻断口径）；occurredAt 缺失或不可解析按解析失败处置。
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
     * 解析帧的双形态结果（sealed 受限子类型，宪法 A.1-3 编译器穷尽检查——消费侧 switch
     * 仅需处理遥测/状态两分支，新增形态编译期强制补齐）。
     */
    public sealed interface ParsedFrame {

        /**
         * 遥测帧解析产物。
         *
         * @param message CF-7 标准遥测消息，非空
         */
        record TelemetryFrame(StandardTelemetryMessage message) implements ParsedFrame {}

        /**
         * 状态帧解析产物。
         *
         * @param event 设备状态变更事件，非空
         */
        record StatusFrame(DeviceStatusEvent event) implements ParsedFrame {}
    }

    /**
     * 解析一帧原始报文为遥测帧或状态帧。
     *
     * @param raw 帧原始字节（消费侧 receive 原文），非空；UTF-8 解码
     * @return 解析产物：遥测帧或状态帧（sealed 两分支），非空
     * @throws FrameParseException 非 JSON 报文、形态判别失败（缺必填字段）、字段值域越界
     *                             （quality/source/status 非法值）或 occurredAt 不可解析；
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
     * 解析状态帧为设备状态变更事件（P0 占位载荷：wardId 状态帧契约不含，恒 null）。
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
        String text = root.path(IotMessagingConstants.FRAME_FIELD_OCCURRED_AT).asText();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // 带偏移解析失败回退 ISO-8601 Instant 形态（如 2026-09-10T00:00:00Z 之外的紧凑形态）
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new FrameParseException("occurredAt 缺失或不可解析（要求 ISO-8601 时间）", e);
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

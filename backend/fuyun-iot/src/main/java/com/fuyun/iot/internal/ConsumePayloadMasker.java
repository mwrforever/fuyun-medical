package com.fuyun.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.utils.SensitiveMasker;
import com.fuyun.iot.constants.IotMessagingConstants;
import java.util.Map;

/**
 * 毒丸留痕脱敏器：iot_consume_error_log.raw_payload 入库前的统一脱敏出口（TASK.md L-3 行自带
 * 义务，终审 Minor 2026-09-11——P0 靠 CF-7 线格式无 PHI 前提实质合规，真实报文映射后必须显式
 * 脱敏）。真实 IoTDA 推送帧的属性值为生命体征数值（健康数据），569 帧毒丸留痕实证禁止原文入库。
 *
 * <p>脱敏口径（按形态分型）：①IoTDA 推送形态（可解析为 JSON 对象且含 notify_data 对象字段，
 * 无论毒因）→ 白名单字段提取——保留 resource/event/event_time_ms、notify_data.header 三设备标识、
 * notify_data.body.services[].service_id 结构与 properties 键名，属性值一律替换为 {@code "*"}
 * （紧凑 JSON 输出；白名单外字段如 services[].event_time 丢弃）；②其余文本（非 JSON/其他形态）
 * → SensitiveMasker 正则兜底（先证后机组合约定，CF-7 P0 契约无 PHI 正则即可）；③null/空串原样。
 *
 * <p>口径联动：消费者 {@code poisonAndAcknowledge} 落库前经 {@link #sanitize} 脱敏，故
 * raw_digest 随之为<b>脱敏后文本</b>的 SHA-256 摘要（此前为原文摘要）——摘要用途为排查锚点与
 * 重复帧对账，脱敏后同构报文摘要合并无害。
 *
 * <p>static 纯函数式、无私有状态（模式对齐 fuyun-common SensitiveMasker，宪法 A.1-9 服务无状态
 * 前提）；ObjectMapper 线程安全可静态复用。归 internal/ 包：容器驱动链路的模块内组件，禁止外部
 * 引用（宪法 B.1）。
 */
public final class ConsumePayloadMasker {

    /** JSON 解析器：ObjectMapper 线程安全，static 单例复用（纯函数式解析无配置定制） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 属性值打码占位符：所有 properties 值统一替换（键名保留供毒因定位） */
    private static final String MASKED_VALUE = "*";

    /**
     * 私有构造器：纯函数工具类禁止实例化（backend 宪法 A.2-6）。
     */
    private ConsumePayloadMasker() {}

    /**
     * 脱敏一帧留痕原文：IoTDA 推送形态走白名单提取，其余文本走 SensitiveMasker 正则兜底。
     *
     * @param rawText 帧原文（UTF-8 解码后），可空；null/空串原样返回（无内容可脱敏）
     * @return 脱敏后文本（IoTDA 形态为紧凑 JSON 白名单产物；其余为正则兜底结果，无命中即原样），
     *         非空（入参非空时）
     */
    public static String sanitize(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawText);
        } catch (Exception e) {
            // 非 JSON 文本：无结构可提取，正则兜底（掩码含星号后互不干扰）
            return regexFallback(rawText);
        }
        if (root.isObject()
                && root.path(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA).isObject()) {
            // IoTDA 推送形态无论毒因一律白名单提取：属性值属健康数据，禁止任何形态入库
            return sanitizeIotdaPush(root);
        }
        return regexFallback(rawText);
    }

    /**
     * IoTDA 推送形态白名单提取：仅保留白名单字段与 properties 键名，属性值全量打码（紧凑 JSON）。
     *
     * @param root 已解析的帧根节点（含 notify_data 对象字段），非空
     * @return 白名单产物紧凑 JSON 文本，非空
     */
    private static String sanitizeIotdaPush(JsonNode root) {
        ObjectNode sanitized = MAPPER.createObjectNode();
        copyIfPresent(sanitized, root, IotMessagingConstants.FRAME_FIELD_RESOURCE);
        copyIfPresent(sanitized, root, IotMessagingConstants.IOTDA_FIELD_EVENT);
        copyIfPresent(sanitized, root, IotMessagingConstants.IOTDA_FIELD_EVENT_TIME_MS);
        ObjectNode notifyData = sanitized.putObject(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA);
        JsonNode header =
                root.path(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA).path(IotMessagingConstants.IOTDA_FIELD_HEADER);
        ObjectNode sanitizedHeader = notifyData.putObject(IotMessagingConstants.IOTDA_FIELD_HEADER);
        copyIfPresent(sanitizedHeader, header, IotMessagingConstants.IOTDA_FIELD_DEVICE_ID);
        copyIfPresent(sanitizedHeader, header, IotMessagingConstants.IOTDA_FIELD_NODE_ID);
        copyIfPresent(sanitizedHeader, header, IotMessagingConstants.IOTDA_FIELD_PRODUCT_ID);
        ArrayNode sanitizedServices = notifyData
                .putObject(IotMessagingConstants.IOTDA_FIELD_BODY)
                .putArray(IotMessagingConstants.IOTDA_FIELD_SERVICES);
        JsonNode services = root.path(IotMessagingConstants.IOTDA_FIELD_NOTIFY_DATA)
                .path(IotMessagingConstants.IOTDA_FIELD_BODY)
                .path(IotMessagingConstants.IOTDA_FIELD_SERVICES);
        for (JsonNode service : services) {
            if (!service.isObject()) {
                // 非对象服务元素无白名单结构可提取，丢弃（白名单外内容一律不入产物）
                continue;
            }
            ObjectNode sanitizedService = sanitizedServices.addObject();
            copyIfPresent(sanitizedService, service, IotMessagingConstants.IOTDA_FIELD_SERVICE_ID);
            JsonNode properties = service.path(IotMessagingConstants.IOTDA_FIELD_PROPERTIES);
            if (!properties.isObject()) {
                continue;
            }
            ObjectNode sanitizedProperties = sanitizedService.putObject(IotMessagingConstants.IOTDA_FIELD_PROPERTIES);
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                // 键名保留（毒因定位与属性结构溯源）、值全量打码（生命体征数值禁入留痕库）
                sanitizedProperties.put(property.getKey(), MASKED_VALUE);
            }
        }
        // 紧凑 JSON 输出（ObjectNode.toString 无美化），与 raw_payload 截断列宽防线协同
        return sanitized.toString();
    }

    /**
     * 白名单字段拷贝：源对象含该字段才拷贝（含 null 字面量，结构保真），缺失跳过。
     *
     * @param target 目标对象，非空
     * @param source 源节点，非空（非对象时路径安全返回缺失）
     * @param field  白名单字段名，非空
     */
    private static void copyIfPresent(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.path(field);
        if (!value.isMissingNode()) {
            target.set(field, value);
        }
    }

    /**
     * 正则兜底脱敏：身份证先于手机号（SensitiveMasker 组合约定——先证后机，防证号中段被手机号
     * 掩码误插星号）；无命中原样返回（CF-7 P0 契约无 PHI，正则兜底即可）。
     *
     * @param text 原文，非空
     * @return 脱敏后文本
     */
    private static String regexFallback(String text) {
        return SensitiveMasker.maskPhone(SensitiveMasker.maskIdCard(text));
    }
}

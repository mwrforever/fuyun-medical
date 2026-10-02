package com.fuyun.nursing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 设备呼叫触发转发消费侧（FU-M05-08 呼叫转发面，Task 11）：订阅 {@code iot.call.triggered}
 * （V1004 id 81 在册 ACTIVE，登记面实测在位——非虚构事件；载荷冻结契约六字段
 * callNo/deviceId/callType/bedId/wardId/triggeredAt）转推护士站大屏 board 主题。
 *
 * <p><b>降级注记（brief 冻结口径）</b>：M16 呼叫状态事件族未发布——本转发为「触发通知」
 * 非全状态同步（应答/结束态不随转发面同步，呼叫全状态归 M16）；转发帧不落库（纯转发面，
 * 呼叫落行归 ward 域 CallTriggeredEventListener 既有链）。
 *
 * <p><b>病区标识空间申报</b>：载荷 wardId 为 iot 域病区 id（BIGINT，sys_org 雪花 id 数字串）
 * ——与护理域病区编码（org_code 文本，如 W01）分属两个标识空间且 nursing 侧无 id→code
 * 映射 api 面（system 无 OrgQuery 出网，实测结论）。故 CALL_TRIGGERED 帧路由 topic 尾段
 * 直用数字串（与前端 iot 三主题订阅的 wardId 形态同源——bigscreen 大屏 iot 面本以数字串
 * 病区 id 订阅）；board 其余四类帧以护理病区编码路由。前端 Task 17 消费时呼叫面按 iot 病区
 * id 订阅或经映射面收口（M16 联调冻结，PR 描述转呈）。
 *
 * <p>消费形态：RabbitMQ 容器 AUTO 确认 + 幂等分域 consumer_module=nursing
 * （nursingConsumerSupport @Qualifier 定绑——GC7 红线）；载荷以 JsonNode 读（消费侧禁依赖
 * 生产者 jar——iot→nursing 已成 api 单向依赖，反向引 iot api 包会成环，IotAlarmExecutionListener
 * 先例）；本监听器零事务（纯转发无写面），推送事件经 NurseBoardPushListener fallback 立即
 * 出站。bedId/callType 等载荷字段缺失防御：定位键 callNo/wardId/triggeredAt 缺失即 ISE
 * 死信留痕，非定位键缺省承载。归 internal/：容器驱动入口禁外引；Bean 注册点
 * NursingMessagingConfig @Import。
 */
@Slf4j
public class IotCallTriggeredListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，必须 @Qualifier 定绑 nursingConsumerSupport——GC7 红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param events          进程内事件发布器，非空；大屏 CALL_TRIGGERED 帧发布（Task 11 接线）
     */
    public IotCallTriggeredListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            ApplicationEventPublisher events) {
        this.consumerSupport = consumerSupport;
        this.events = events;
    }

    /**
     * 呼叫触发消费入口（q.nursing.iot.call.triggered，V1004 id 81）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.nursing.iot.call.triggered
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_IOT_CALL_TRIGGERED)
    public void onCallTriggered(Message message) {
        consumerSupport.consume(message, this::handleCallTriggered);
    }

    /**
     * 转发业务体（包级直驱可测）：载荷冻结子集读取 → CALL_TRIGGERED 大屏帧发布（触发通知）。
     *
     * @param envelope 事件信封，非空；载荷契约 V1004 id 81 冻结（六字段）
     * @throws IllegalStateException 缺 callNo/wardId/triggeredAt 定位键或时点非法（不合规帧禁
     *                               静默吞——死信留痕）时触发
     */
    void handleCallTriggered(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String callNo = requireText(envelope, payload, "callNo");
        // wardId 为 iot 域数字标识（长整型文本承载——标识空间申报见类注释）
        String wardIdText = requireText(envelope, payload, "wardId");
        long wardId;
        try {
            wardId = Long.parseLong(wardIdText);
        } catch (NumberFormatException e) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "呼叫触发载荷不合规（wardId 非数字标识）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
        Instant triggeredAt = requireInstant(envelope, payload, "triggeredAt");
        String deviceId = textOrNull(payload, "deviceId");
        String callType = textOrNull(payload, "callType");
        JsonNode bedNode = payload.path("bedId");
        Long bedId = bedNode.isMissingNode() || bedNode.isNull() ? null : bedNode.asLong();
        // 消息发送：大屏呼叫转发帧（触发通知非全状态同步——M16 降级注记；路由病区=iot 数字串）
        events.publishEvent(new NurseBoardPushEvent(
                Long.toString(wardId),
                NurseBoardPushFrame.TYPE_CALL_TRIGGERED,
                new NurseBoardPushFrame.CallTriggeredPayload(callNo, deviceId, callType, bedId, wardId, triggeredAt),
                triggeredAt));
        log.info(
                "设备呼叫触发已转发大屏（触发通知非全状态同步，M16 降级注记）：callNo={}，deviceId={}，callType={}，wardId={}，bedId={}，event_id={}",
                callNo,
                deviceId,
                callType,
                wardId,
                bedId,
                envelope.eventId());
    }

    /**
     * 载荷必填文本守卫（缺失/空白即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 字段文本值，非空
     */
    private static String requireText(EventEnvelope envelope, JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (value == null) {
            throw new IllegalStateException(
                    "呼叫触发载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }

    /**
     * 载荷必填时点守卫（ISO-8601 Instant 文本；缺失/不可解析即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 时点，非空
     */
    private static Instant requireInstant(EventEnvelope envelope, JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (value == null) {
            throw new IllegalStateException(
                    "呼叫触发载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "呼叫触发载荷不合规（" + field + " 非法时点文本）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null）。
     *
     * @param payload 载荷 JSON，非空
     * @param field   字段名，非空
     * @return 字段文本值，可空
     */
    private static String textOrNull(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}

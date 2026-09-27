package com.fuyun.iot.service.impl;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.service.ITelemetryPushService;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 遥测 STOMP 推送服务实现（P0 直推语义，BRIEF-PR4-01 §4）：经 {@link SimpMessagingTemplate}
 * 向内存 SimpleBroker（/topic 前缀）分发，进程内直达已订阅 WebSocket 会话。调用方须遵守宪法
 * A.4.2-7"事务内禁止远程调用、消息发送与人工等待，对外调用在事务提交后执行"——本类不感知
 * 事务，由调用侧保证时序：入库侧 TelemetryIngestServiceImpl 以 afterCommit 回调承接，消费
 * 监听器侧运行于 MQ 消费线程（无事务上下文）。推送失败原样向调用方抛出，由调用方按各自语义
 * 处置（入库侧吞并告警、监听器侧业务失败重抛）。
 *
 * <p>无状态单例：SimpMessagingTemplate 线程安全（Spring 官方契约），可多线程并发推送。
 * 装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryPushServiceImpl implements ITelemetryPushService {

    /** STOMP 发送模板：SimpleBroker 通道唯一发送口（来源：@EnableWebSocketMessageBroker 基础设施） */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param messagingTemplate STOMP 发送模板，非空；来源：IotWebSocketConfig 消息代理基础设施
     */
    public TelemetryPushServiceImpl(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void pushSummary(List<IotTelemetryEntity> written, Long wardId) {
        // 跳过分支：无绑定快照（wardId null）或空批次不推送（简报 §1.4"无绑定快照的帧仅落库"）
        if (wardId == null || written == null || written.isEmpty()) {
            log.info("遥测摘要推送跳过（无病区归属或空批次）：wardId={}，count={}", wardId, written == null ? 0 : written.size());
            return;
        }
        // 载荷轻量化（防 1009 大消息）：items 截至上限，条数保留真实值（订阅方以 count 为准）
        List<Item> items = written.stream()
                .limit(SUMMARY_MAX_ITEMS)
                .map(entity -> new Item(entity.getDeviceId(), entity.getMetricCode()))
                .toList();
        // occurredAt 上界 = 本批最大发生时刻（摘要时间锚点；批次非空才有推送，max 必有值）
        Instant upperBound = written.stream()
                .map(entity -> entity.getOccurredAt().toInstant())
                .max(Instant::compareTo)
                .orElseThrow();
        TelemetrySummary summary = new TelemetrySummary(written.size(), upperBound, items);
        messagingTemplate.convertAndSend(IotMessagingConstants.TOPIC_TELEMETRY_PREFIX + wardId, summary);
        log.info(
                "遥测摘要帧已推送：topic={}，count={}，items={}，occurredAtUpperBound={}",
                IotMessagingConstants.TOPIC_TELEMETRY_PREFIX + wardId,
                summary.count(),
                items.size(),
                upperBound);
    }

    @Override
    public void pushDeviceStatus(DeviceStatusEvent event) {
        // 跳过分支：载荷 wardId 为空（P0 状态帧契约不含 wardId）info 跳过推送（简报 §1.5 口径）
        if (event.wardId() == null) {
            log.info("设备状态帧推送跳过（载荷无病区归属）：deviceId={}，status={}", event.deviceId(), event.status());
            return;
        }
        // 载荷 = 事件契约 record 本体（deviceId/status/occurredAt/wardId 四字段同构，简报 §1.5）
        messagingTemplate.convertAndSend(IotMessagingConstants.TOPIC_DEVICE_STATUS_PREFIX + event.wardId(), event);
        log.info(
                "设备状态帧已推送：topic={}{}，deviceId={}，status={}，occurredAt={}",
                IotMessagingConstants.TOPIC_DEVICE_STATUS_PREFIX,
                event.wardId(),
                event.deviceId(),
                event.status(),
                event.occurredAt());
    }

    @Override
    public void pushAlarm(IotAlarmEntity alarm) {
        // 跳过分支：wardId 为空属引擎侧防御缺口（病区路由缺失的告警在引擎已跳过新发），info 留痕
        if (alarm.getWardId() == null) {
            log.info("告警帧推送跳过（告警行无病区归属）：alarmNo={}", alarm.getAlarmNo());
            return;
        }
        messagingTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_ALARM_PREFIX + alarm.getWardId(), buildAlarmPayload(alarm));
        log.info(
                "告警帧已推送：topic={}{}，alarmNo={}，level={}，deviceId={}",
                IotMessagingConstants.TOPIC_ALARM_PREFIX,
                alarm.getWardId(),
                alarm.getAlarmNo(),
                alarm.getAlarmLevel(),
                alarm.getDeviceId());
    }

    @Override
    public void pushLinkageNotify(IotAlarmEntity alarm, String linkageNo) {
        // 跳过分支：wardId 为空无法定推告警主题（与 pushAlarm 同口径 info 留痕，联动侧按回执裁决）
        if (alarm.getWardId() == null) {
            log.info("联动强提醒推送跳过（告警行无病区归属）：alarmNo={}，linkageNo={}", alarm.getAlarmNo(), linkageNo);
            return;
        }
        // 载荷与告警帧同构（重复强化语义），linkageNo 经 STOMP 头携带作联动标记（不改冻结载荷契约）
        messagingTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_ALARM_PREFIX + alarm.getWardId(),
                buildAlarmPayload(alarm),
                java.util.Map.of("linkageNo", linkageNo));
        log.info(
                "联动强提醒帧已推送（带 linkage 标记头）：topic={}{}，alarmNo={}，linkageNo={}",
                IotMessagingConstants.TOPIC_ALARM_PREFIX,
                alarm.getWardId(),
                alarm.getAlarmNo(),
                linkageNo);
    }

    /**
     * 构造告警主题帧载荷（pushAlarm/pushLinkageNotify 共用）：与 iot.alarm.triggered 事件契约
     * record 同构，字段一一对应告警行快照。
     *
     * @param alarm 已落库告警实体，非空
     * @return triggered 契约载荷，非空
     */
    private static AlarmTriggeredPayload buildAlarmPayload(IotAlarmEntity alarm) {
        return new AlarmTriggeredPayload(
                alarm.getAlarmNo(),
                alarm.getDeviceId(),
                alarm.getPatientId(),
                alarm.getVisitId(),
                alarm.getWardId(),
                alarm.getAlarmLevel().getCode(),
                alarm.getMetricCode(),
                alarm.getTriggerValue(),
                alarm.getRuleId(),
                alarm.getLastTriggeredAt().toInstant());
    }
}

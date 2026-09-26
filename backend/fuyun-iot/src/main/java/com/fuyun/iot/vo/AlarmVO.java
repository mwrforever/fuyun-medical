package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmStatus;
import java.time.OffsetDateTime;

/**
 * 告警视图对象（告警域出网载体）：告警行全字段出网（绑定快照五元组 + 生命周期 + 升级计数）。
 * 实体禁直出（宪法 B.1 出网边界），查询/确认/关闭响应统一经 {@link #from} 静态工厂转换。
 *
 * @param id               告警行雪花 id，非空
 * @param alarmNo          告警业务号，非空
 * @param ruleId           命中规则 ID，非空
 * @param deviceId         告警设备号，非空
 * @param patientId        患者主索引，可空
 * @param visitId          住院就诊号（CF-3 I 型 14 位），可空
 * @param wardId           病区 ID，非空
 * @param alarmLevel       告警级别，非空
 * @param metricCode       指标编码（离线告警为 DEVICE_OFFLINE），非空
 * @param triggerValue     触发值原文，非空
 * @param status           告警状态，非空
 * @param triggerCount     累计触发次数，非空
 * @param lastTriggeredAt  最近触发时刻，非空
 * @param escalationCount  已升级次数，非空
 * @param lastEscalatedAt  最近升级时刻，可空
 * @param acknowledgedBy   确认人，可空
 * @param acknowledgedAt   确认时刻，可空
 * @param closedBy         关闭人，可空
 * @param closedAt         关闭时刻，可空
 * @param closeReason      关闭原因，可空
 * @param traceId          全链路追踪号，可空
 * @param createdAt        创建（首次触发）时刻，非空
 */
public record AlarmVO(
        Long id,
        String alarmNo,
        Long ruleId,
        String deviceId,
        Long patientId,
        String visitId,
        Long wardId,
        AlarmLevel alarmLevel,
        String metricCode,
        String triggerValue,
        AlarmStatus status,
        Integer triggerCount,
        OffsetDateTime lastTriggeredAt,
        Integer escalationCount,
        OffsetDateTime lastEscalatedAt,
        String acknowledgedBy,
        OffsetDateTime acknowledgedAt,
        String closedBy,
        OffsetDateTime closedAt,
        String closeReason,
        String traceId,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 告警实体，非空；来源：mapper 查询或落库组装
     * @return 告警视图，非空
     */
    public static AlarmVO from(IotAlarmEntity entity) {
        return new AlarmVO(
                entity.getId(),
                entity.getAlarmNo(),
                entity.getRuleId(),
                entity.getDeviceId(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getWardId(),
                entity.getAlarmLevel(),
                entity.getMetricCode(),
                entity.getTriggerValue(),
                entity.getStatus(),
                entity.getTriggerCount(),
                entity.getLastTriggeredAt(),
                entity.getEscalationCount(),
                entity.getLastEscalatedAt(),
                entity.getAcknowledgedBy(),
                entity.getAcknowledgedAt(),
                entity.getClosedBy(),
                entity.getClosedAt(),
                entity.getCloseReason(),
                entity.getTraceId(),
                entity.getCreatedAt());
    }
}

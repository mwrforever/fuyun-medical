package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 告警关闭事件载荷（iot.alarm.closed，V1004 id 76 冻结契约）：告警终态（人工关闭/自动恢复）时
 * 发布，M05/M16 据此复位提醒与统计闭环。
 *
 * @param alarmNo    告警业务号，非空；来源：被关闭告警
 * @param deviceId   IoTDA 设备标识，非空；来源：被关闭告警关联设备
 * @param wardId     病区 ID，非空；来源：被关闭告警病区
 * @param closedBy   关闭人，非空（自动恢复场景为系统标识 system）；来源：关闭操作上下文或恢复引擎
 * @param closedAt   关闭时点（UTC），非空；来源：关闭动作时刻
 * @param closeReason 关闭原因，非空；来源：操作者填写或自动恢复判定结论
 */
public record AlarmClosedPayload(
        String alarmNo, String deviceId, Long wardId, String closedBy, Instant closedAt, String closeReason) {}

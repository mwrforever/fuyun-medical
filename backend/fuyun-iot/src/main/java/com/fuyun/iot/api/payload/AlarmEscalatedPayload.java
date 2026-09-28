package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 告警升级动作事件载荷（iot.alarm.escalated，V1004 id 75 冻结契约）：挂单升级到期执行升级动作时
 * 发布——升级为动作广播而非告警状态迁移，M05/M16 据此提升提醒强度（重播报/上级推送）。
 *
 * @param alarmNo         告警业务号，非空；来源：被升级告警
 * @param deviceId        IoTDA 设备标识，非空；来源：被升级告警关联设备
 * @param wardId          病区 ID，非空；来源：被升级告警病区
 * @param escalationLevel 升级档位，非空（1 起递增，档位语义归 M05 挂单升级策略）；来源：升级动作
 *                        执行时计算
 * @param escalatedAt     升级时点（UTC），非空；来源：升级动作执行时刻
 */
public record AlarmEscalatedPayload(
        String alarmNo, String deviceId, Long wardId, int escalationLevel, Instant escalatedAt) {}

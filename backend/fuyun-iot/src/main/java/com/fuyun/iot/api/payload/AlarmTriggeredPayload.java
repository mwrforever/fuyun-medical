package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 告警触发事件载荷（iot.alarm.triggered，V1004 id 74 冻结契约）：告警引擎命中规则时发布，
 * M05 据此驱动挂单升级、M16 据此做病区播报消费。
 *
 * @param alarmNo      告警业务号，非空；来源：告警引擎告警生成域签发
 * @param deviceId     IoTDA 设备标识，非空；来源：触发规则的遥测/状态帧来源设备
 * @param patientId    患者主索引，可空（无患者关联设备的公共区域告警为 null）；来源：设备绑定档案
 * @param visitId      住院就诊号（CF-3 I 型 visit_id），可空（设备未绑定在院患者为 null）；
 *                     来源：设备绑定档案
 * @param wardId       病区 ID，非空；来源：设备绑定档案（告警按病区路由播报）
 * @param alarmLevel   告警级别，非空；值域 INFO/WARNING/CRITICAL；来源：命中规则级别
 * @param metricCode   指标编码，非空；来源：触发判定的遥测指标（体征/环境类 metric 字典）
 * @param triggerValue 触发值，非空；来源：命中判定时刻的采集值文本（保留原始形态，数值语义由
 *                     消费方按 metricCode 解释）
 * @param ruleId       命中规则 ID，非空；来源：alarm_rule 表主键
 * @param occurredAt   业务发生时刻（UTC），非空；来源：命中判定时刻
 */
public record AlarmTriggeredPayload(
        String alarmNo,
        String deviceId,
        Long patientId,
        String visitId,
        Long wardId,
        String alarmLevel,
        String metricCode,
        String triggerValue,
        Long ruleId,
        Instant occurredAt) {}

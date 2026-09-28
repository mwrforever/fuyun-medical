package com.fuyun.iot.record;

import java.time.OffsetDateTime;

/**
 * 设备×指标末次采集时点投影（质量监控断流判定查询行，mapper XML 构造器映射载体）。
 *
 * <p>record 纯数据载体（宪法 A.1-2）；列名与组件名一一同源（RefundRequestMapper 构造器映射
 * 同款先例）。仅服务内断流判定消费，非对外契约，落 record 包（宪法 B.1 mapper 投影内聚）。
 *
 * @param deviceId      IoTDA 设备标识，非空
 * @param metricCode    指标编码，非空
 * @param lastOccurredAt 末次有效采集时刻（value 非空行，UTC），可空（无采集行为 NULL——聚合查询
 *                      LEFT JOIN 或无行场景由调用方以缺行表达"从未上报"）
 */
public record DeviceMetricLastRow(String deviceId, String metricCode, OffsetDateTime lastOccurredAt) {}

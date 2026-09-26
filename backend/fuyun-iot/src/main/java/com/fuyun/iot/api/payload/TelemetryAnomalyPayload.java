package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 遥测断流异常事件载荷（iot.telemetry.anomaly，V1004 id 78 冻结契约）：设备指标断流/质量异常
 * 判定时发布，M16 据此做体征质量确认提示（数据可信度降级告知）。
 *
 * @param deviceId       IoTDA 设备标识，非空；来源：异常判定设备
 * @param metricCode     指标编码，非空；来源：断流判定的遥测指标
 * @param anomalyType    异常类型，非空（断流/超阈值频率等质量异常分类，值域随质量监控域冻结）；
 *                       来源：质量监控引擎判定
 * @param lastOccurredAt 末次正常采集时刻（UTC），可空（设备从未成功上报为 null）；来源：
 *                       iot_telemetry 末帧时点
 * @param detectedAt     异常检出时刻（UTC），非空；来源：质量监控引擎判定时刻
 */
public record TelemetryAnomalyPayload(
        String deviceId, String metricCode, String anomalyType, Instant lastOccurredAt, Instant detectedAt) {}

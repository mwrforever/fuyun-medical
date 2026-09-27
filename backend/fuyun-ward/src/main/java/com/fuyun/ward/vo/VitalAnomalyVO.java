package com.fuyun.ward.vo;

import java.time.OffsetDateTime;

/**
 * 体征采集质量注记视图（GET /api/v1/ward/vital-board/{wardId} 出参 anomalies 内嵌载体）：
 * iot.telemetry.anomaly 消费落 Redis 的断流异常快照（deviceId 维度）。
 *
 * @param deviceId     IoTDA 设备标识，非空
 * @param metricCode   断流指标编码，非空
 * @param anomalyType  异常类型（STREAM_GAP 断流——iot 词表），非空
 * @param lastOccurredAt 最后发生时刻，可空（载荷缺省）
 * @param detectedAt   检出时刻，非空
 */
public record VitalAnomalyVO(
        String deviceId,
        String metricCode,
        String anomalyType,
        OffsetDateTime lastOccurredAt,
        OffsetDateTime detectedAt) {}

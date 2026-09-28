package com.fuyun.iot.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 遥测最新值视图（GET /api/v1/iot/telemetry/latest 出参）：Redis 快照兜底端点载荷。
 *
 * @param deviceId    设备标识，非空（请求回显）
 * @param metricCode  指标编码，非空（请求回显）
 * @param value       最新采集值；快照缺席为 null
 * @param occurredAt  采集发生时刻（UTC）；快照缺席为 null
 */
public record TelemetryLatestVO(String deviceId, String metricCode, BigDecimal value, OffsetDateTime occurredAt) {

    /**
     * 构造快照缺席视图（value/occurredAt 置 null，请求标识回显）。
     *
     * @param deviceId   设备标识，非空
     * @param metricCode 指标编码，非空
     * @return 空值视图，非空
     */
    public static TelemetryLatestVO empty(String deviceId, String metricCode) {
        return new TelemetryLatestVO(deviceId, metricCode, null, null);
    }
}

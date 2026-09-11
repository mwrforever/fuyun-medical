package com.fuyun.iot.dto;

import com.fuyun.iot.enums.TelemetryQuality;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * HTTP 兜底通道入库请求体（POST /ingest/iotda-fallback，BRIEF-PR4-01 §4 dto 行）。
 *
 * <p>CF-7 同形 JSON（七字段线格式的 HTTP 载体）：与 AMQP 主链路解析目标共用
 * {@link com.fuyun.common.messaging.StandardTelemetryMessage} 统一语言，入库走同一管道
 * （同一唯一约束幂等与绑定快照语义）。quality 缺省 GOOD（compact 构造器补默认值，与
 * TelemetryFrameParser 解析器缺省口径一致）；source 由服务端定为 IOTDA（不开放客户端传入）。
 *
 * <p>record 纯数据载体（宪法 A.1-2）；请求与响应分建（宪法 A.7-2）。
 *
 * @param deviceId   IoTDA 设备标识，非空；来源：IoTDA 联动规则推送载荷
 * @param metricCode 指标编码（P0 原生编码直传），非空；来源：同上
 * @param value      原始值字符串承载（数值定型归入库管道），非空；来源：同上
 * @param unit       计量单位（无量纲指标为空），可空；来源：同上
 * @param occurredAt 发生时刻（UTC 语义，ISO-8601；入库幂等唯一键三列之一），非空；来源：同上
 * @param quality    数据质量（GOOD/SUSPECT/BAD），缺省 GOOD；来源：同上（缺省补齐见构造器）
 */
public record FallbackIngestRequest(
        @NotBlank(message = "deviceId 不能为空") String deviceId,
        @NotBlank(message = "metricCode 不能为空") String metricCode,
        @NotBlank(message = "value 不能为空") String value,
        String unit,
        @NotNull(message = "occurredAt 不能为空") Instant occurredAt,
        String quality) {

    /**
     * 紧凑构造器：quality 缺省补 GOOD（JSON 未携带该字段时 Jackson 走本构造器，默认值与
     * AMQP 解析器缺省口径一致）。
     */
    public FallbackIngestRequest {
        if (quality == null || quality.isBlank()) {
            quality = TelemetryQuality.GOOD.getCode();
        }
    }
}

package com.fuyun.iot.vo;

/**
 * HTTP 兜底通道入库响应体（POST /ingest/iotda-fallback，BRIEF-PR4-01 §4 vo 行）。
 *
 * <p>202 Accepted 语义：帧已受理并走完入库管道（同步完成），accepted 恒 true——入库结果
 * （含唯一键冲突忽略）以 iot_telemetry 实际行为准，本响应仅表达"受理成功"；
 * 鉴权失败走 ProblemDetail 401 IOT-1001（不入本响应形态）。
 *
 * <p>record 纯数据载体（宪法 A.1-2）；请求与响应分建（宪法 A.7-2）。
 *
 * @param accepted 是否受理成功（恒 true；202 状态码已隐含，字段为显式契约便于订阅方断言）
 */
public record FallbackIngestResponse(boolean accepted) {}

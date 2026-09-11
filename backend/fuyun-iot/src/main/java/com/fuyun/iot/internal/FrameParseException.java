package com.fuyun.iot.internal;

/**
 * 遥测帧解析失败异常：AMQP 消费链毒丸标记（BRIEF-PR4-01 §3 internal/TelemetryFrameParser 行）。
 *
 * <p>抛出场景：帧非 JSON、字段形态判别失败（缺必填字段）、字段值域越界（quality/status 非法值）、
 * 时间格式不可解析。消费侧捕获后落 iot_consume_error_log（stage=PARSE）并确认抛弃该毒丸，
 * 不阻塞队列（FU-M14-01 毒丸隔离）。继承 RuntimeException（宪法 A.1-6 禁 checked 业务异常）。
 * 异常消息只含字段名与原因，禁携带帧原文（载荷原文经摘要与截断引用进错误日志，敏感红线）。
 */
public class FrameParseException extends RuntimeException {

    /**
     * 构造解析失败异常。
     *
     * @param message 中文失败原因（含字段名定位），非空；禁止携带帧原文与敏感值
     */
    public FrameParseException(String message) {
        super(message);
    }

    /**
     * 构造带底层原因的解析失败异常（如 Jackson 解析异常、时间解析异常）。
     *
     * @param message 中文失败原因（含字段名定位），非空；禁止携带帧原文与敏感值
     * @param cause   底层异常，非空；仅保留类型与消息供排障，调用方禁将其消息全量入库
     */
    public FrameParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

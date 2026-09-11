package com.fuyun.iot.service;

/**
 * 消费错误日志服务：AMQP 主链路失败消息的毒丸留痕写入口（M14 FU-M14-01，V401 表）。
 *
 * <p>毒丸隔离口径：解析失败帧落本表（stage=PARSE）后确认抛弃，不阻塞消费队列；
 * 落库失败全吞仅告警（毒丸隔离优先于留痕，DeadLetterListener 同源语义）。P0 只建写路径，
 * 重放/放弃管理端点随 P1。
 */
public interface IConsumeErrorLogService {

    /**
     * 留痕一次消费失败：原文 SHA-256 摘要 + 载荷脱敏截断引用 + PENDING 行落库。
     *
     * <p>永不向调用方抛出（含落库失败——catch 全吞后 error 告警返回），保证毒丸隔离路径
     * 不因留痕写入失败而中断消费循环（javadoc 声明优先级：隔离优先于留痕）。
     *
     * @param queueName 来源订阅队列名（溯源消费链路），非空；来源：消费者配置的队列清单
     * @param rawText   帧原文（UTF-8 解码后），非空；仅用于摘要计算与截断留痕，
     *                  调用方须已脱敏或保证无敏感明文（禁入日志）
     * @param stage     失败阶段字面量（PARSE/VALIDATE/PERSIST，ConsumeErrorStage 值域），非空；
     *                  非法值抛 IllegalArgumentException（调用方编程错误，应修正传参）
     * @param errorMsg  失败原因摘要（不带原文敏感值），可空；超 500 字符截断（列宽防线）
     */
    void recordParseFailure(String queueName, String rawText, String stage, String errorMsg);
}

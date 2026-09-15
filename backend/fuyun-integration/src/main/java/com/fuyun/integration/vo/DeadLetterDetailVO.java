package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 死信详情出参（GET /api/v1/integration/dead-letters/{id}）：列表行全部字段 + 载荷全文。
 *
 * <p>载荷全文供运维诊断（M20 §3.2「诊断：看载荷、失败原因、源队列」）；本端点用于重放前人工
 * 核对原文，禁止在列表端点返回全文。
 *
 * @param id             死信 ID（雪花 ID），非空；JSON 输出为字符串
 * @param sourceQueue    来源队列，非空
 * @param routingKey     原始路由键，可空
 * @param eventType      事件类型，可空
 * @param eventId        信封 eventId，可空
 * @param payloadBody    载荷原文全文（重放依赖同一原文），非空
 * @param payloadDigest  载荷 SHA-256 摘要，可空
 * @param failReason     死信原因，非空
 * @param firstDeadAt    首次死信时间，非空
 * @param status         处理状态，非空
 * @param replayCount    重放计数，非空
 * @param handler        处理人标识，可空
 * @param handleNote     处理备注，可空
 * @param handledAt      处理时间，可空
 */
public record DeadLetterDetailVO(
        Long id,
        String sourceQueue,
        String routingKey,
        String eventType,
        String eventId,
        String payloadBody,
        String payloadDigest,
        String failReason,
        OffsetDateTime firstDeadAt,
        String status,
        Integer replayCount,
        String handler,
        String handleNote,
        OffsetDateTime handledAt) {}

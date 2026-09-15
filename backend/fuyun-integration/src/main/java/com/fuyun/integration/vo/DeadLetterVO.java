package com.fuyun.integration.vo;

import java.time.OffsetDateTime;

/**
 * 死信列表行出参（GET /api/v1/integration/dead-letters）。
 *
 * <p>record 透明浅不可变载体（A.1-2）。载荷只出头部预览与摘要，全文经详情端点
 * （最小暴露：列表可能一次返回多帧原文，避免大字段与敏感内容批量外泄）。
 *
 * @param id             死信 ID（雪花 ID），非空；JSON 输出为字符串
 * @param sourceQueue    来源队列（x-death[].queue），非空
 * @param routingKey     原始路由键（=事件类型），可空（轨迹缺失帧）
 * @param eventType      事件类型，可空（信封不合规帧为空）
 * @param eventId        信封 eventId，可空（信封不合规帧为空）；死信溯源锚点
 * @param payloadDigest  载荷 SHA-256 摘要（64 位十六进制），可空
 * @param payloadPreview 载荷头部预览（不超过 DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH 字符），可空
 * @param failReason     死信原因，非空
 * @param firstDeadAt    首次死信时间，非空
 * @param status         处理状态（PENDING/REPLAYED/CLOSED），非空
 * @param replayCount    重放计数（含失败重放），非空
 * @param handler        处理人标识，可空（尚未处理）
 * @param handleNote     处理备注（关闭原因），可空
 * @param handledAt      处理时间，可空（尚未处理）
 */
public record DeadLetterVO(
        Long id,
        String sourceQueue,
        String routingKey,
        String eventType,
        String eventId,
        String payloadDigest,
        String payloadPreview,
        String failReason,
        OffsetDateTime firstDeadAt,
        String status,
        Integer replayCount,
        String handler,
        String handleNote,
        OffsetDateTime handledAt) {}

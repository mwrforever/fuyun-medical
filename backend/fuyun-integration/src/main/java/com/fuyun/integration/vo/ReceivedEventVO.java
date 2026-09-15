package com.fuyun.integration.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 消费台账行出参（GET /api/v1/integration/received-events，M20 Spec §4 received_event 字段面）。
 *
 * @param id             台账行 ID（雪花 ID），非空；JSON 输出为字符串
 * @param eventId        信封 eventId（UUID），非空
 * @param eventType      事件类型，非空
 * @param producer       生产模块域标识，非空
 * @param occurredAt     事件发生时刻（信封字段），非空
 * @param consumerModule 消费者模块域标识，非空
 * @param status         消费状态：PROCESSED 已消费 / FAILED 消费失败（重试中），非空
 * @param failReason     消费失败原因，可空（PROCESSED 行清空）
 * @param retryCount     消费失败登记次数（0=未失败过；容器侧重试不落库，口径见 PR-1b 计划），非空
 * @param receivedAt     接收时间（数据库维护），非空
 * @param processedAt    处理完成时间，可空（FAILED 行未完成）
 */
public record ReceivedEventVO(
        Long id,
        UUID eventId,
        String eventType,
        String producer,
        OffsetDateTime occurredAt,
        String consumerModule,
        String status,
        String failReason,
        Integer retryCount,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt) {}

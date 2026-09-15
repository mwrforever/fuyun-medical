package com.fuyun.integration.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 消费台账查询条件（GET /api/v1/integration/received-events，A.7-1 参数对象化）。
 *
 * @param eventType      事件类型过滤（如 system.dict.published），可空 = 不过滤
 * @param eventId        信封 eventId 过滤（UUID，全链溯源锚点），可空 = 不过滤
 * @param consumerModule 消费者模块域标识过滤，可空 = 不过滤
 * @param status         消费状态过滤（PROCESSED/FAILED），可空 = 不过滤
 * @param receivedFrom   接收时间下界（含），可空 = 不限
 * @param receivedTo     接收时间上界（含），可空 = 不限
 * @param page           页码（0 基，宪法 A.3-6），缺省 0
 * @param size           单页条数（1-200），缺省 20
 */
public record ReceivedEventQuery(
        String eventType,
        UUID eventId,
        String consumerModule,
        String status,
        OffsetDateTime receivedFrom,
        OffsetDateTime receivedTo,
        int page,
        int size) {}

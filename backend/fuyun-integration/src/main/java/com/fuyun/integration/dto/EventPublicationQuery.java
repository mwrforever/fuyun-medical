package com.fuyun.integration.dto;

import java.time.OffsetDateTime;

/**
 * 投递台账查询条件（GET /api/v1/integration/event-publications，A.7-1 参数对象化）。
 *
 * @param eventType     事件类型全限定名过滤（框架记录形态），可空 = 不过滤
 * @param status        完成态过滤（COMPLETED/INCOMPLETE），可空 = 不过滤
 * @param publishedFrom 发布时间下界（含），可空 = 不限
 * @param publishedTo   发布时间上界（含），可空 = 不限
 * @param page          页码（0 基，宪法 A.3-6），缺省 0
 * @param size          单页条数（1-200），缺省 20
 */
public record EventPublicationQuery(
        String eventType,
        String status,
        OffsetDateTime publishedFrom,
        OffsetDateTime publishedTo,
        int page,
        int size) {}

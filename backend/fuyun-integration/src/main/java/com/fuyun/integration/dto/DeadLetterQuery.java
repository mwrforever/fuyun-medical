package com.fuyun.integration.dto;

/**
 * 死信列表查询条件（GET /api/v1/integration/dead-letters，A.7-1 参数对象化）。
 *
 * @param status      处理状态过滤（PENDING/REPLAYED/CLOSED），可空 = 不过滤
 * @param eventType   事件类型过滤，可空 = 不过滤
 * @param eventId     信封 eventId 过滤（死信溯源锚点），可空 = 不过滤
 * @param sourceQueue 来源队列过滤，可空 = 不过滤
 * @param page        页码（0 基，宪法 A.3-6），非空；来源：请求参数（缺省 0）
 * @param size        单页条数（1-200），非空；来源：请求参数（缺省 20）
 */
public record DeadLetterQuery(
        String status, String eventType, String eventId, String sourceQueue, int page, int size) {}

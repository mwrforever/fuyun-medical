package com.fuyun.integration.dto;

/**
 * 事件契约台账查询条件（GET /api/v1/integration/event-registry，A.7-1 参数对象化）。
 *
 * @param eventType      事件类型过滤，可空 = 不过滤
 * @param producerModule 生产模块域标识过滤，可空 = 不过滤
 * @param status         契约状态过滤（ACTIVE/DEPRECATED），可空 = 不过滤
 * @param page           页码（0 基，宪法 A.3-6），缺省 0
 * @param size           单页条数（1-200），缺省 20
 */
public record EventRegistryQuery(String eventType, String producerModule, String status, int page, int size) {}

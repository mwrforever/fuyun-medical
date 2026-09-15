package com.fuyun.integration.dto;

/**
 * 主数据订阅矩阵查询条件（GET /api/v1/integration/mdm-subscriptions，A.7-1 参数对象化）。
 *
 * @param topic            主题过滤，可空 = 不过滤
 * @param subscriberModule 订阅方过滤，可空 = 不过滤
 * @param page             页码（0 基），缺省 0
 * @param size             单页条数（1-200），缺省 20
 */
public record MdmSubscriptionQuery(String topic, String subscriberModule, int page, int size) {}

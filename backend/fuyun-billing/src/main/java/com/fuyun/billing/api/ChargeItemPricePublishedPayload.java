package com.fuyun.billing.api;

import java.time.Instant;

/**
 * 调价生效广播事件载荷（billing.charge-item-price.published，CF-4，V605 id 22；工作站价格缓存刷新）。
 *
 * @param chargeItemId  项目 id
 * @param itemCode      项目编码
 * @param priceVersion  生效版本号
 * @param price         单价（分）
 * @param effectiveFrom 生效时刻
 */
public record ChargeItemPricePublishedPayload(
        Long chargeItemId, String itemCode, Integer priceVersion, Long price, Instant effectiveFrom) {}

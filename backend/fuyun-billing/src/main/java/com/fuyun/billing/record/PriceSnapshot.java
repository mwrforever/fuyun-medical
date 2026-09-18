package com.fuyun.billing.record;

import java.math.BigDecimal;

/**
 * 计费时点价格快照值对象（方案 3.4：版本化价格冻结为费用行冗余列的唯一中间载体）。
 *
 * @param chargeItemId   项目 id
 * @param unitPrice      单价（分，当前生效版本）
 * @param priceVersion   价格版本号（历史重现锚点，快照不漂移 IT 断言目标）
 * @param nhsaCode       医保 22 项编码；null=未对照（仅自费，禁医保结算）
 * @param catalogVersion 目录版本；null 同上
 * @param selfPayRatio   先自付比例（0-1）；null 同上
 * @param limitPrice     医保限价（分）；null=无限价
 */
public record PriceSnapshot(
        long chargeItemId,
        long unitPrice,
        int priceVersion,
        String nhsaCode,
        String catalogVersion,
        BigDecimal selfPayRatio,
        Long limitPrice) {}

package com.fuyun.billing.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预计价结果（不含 id，金额均为分）。
 *
 * @param visitId     就诊号
 * @param totalAmount 合计（分）
 * @param lines       逐行结果
 */
public record QuoteVO(String visitId, Long totalAmount, List<Line> lines) {

    /**
     * 计价行结果。
     *
     * @param itemId         项目 id
     * @param itemCode       项目编码
     * @param itemName       项目名称
     * @param unitPrice      单价快照（分）
     * @param quantity       数量
     * @param amount         行金额（分）
     * @param selfExpenseOnly 无有效医保对照（仅自费，贯标硬校验提示）
     */
    public record Line(
            Long itemId,
            String itemCode,
            String itemName,
            Long unitPrice,
            BigDecimal quantity,
            Long amount,
            boolean selfExpenseOnly) {}
}

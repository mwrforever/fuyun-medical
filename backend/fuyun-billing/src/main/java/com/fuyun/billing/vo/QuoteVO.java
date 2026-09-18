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
public record QuoteVO(String visitId, Long totalAmount, List<QuoteLine> lines) {

    /**
     * 计价行结果。
     *
     * <p>类名特意取 QuoteLine 而非 Line：与 {@code QuoteRequest.Line} 简单名相同会被
     * springdoc 按简单名合并为同一 schema，响应行的 unitPrice/amount/selfExpenseOnly
     * 将丢失（前端契约经生成物唯一来源，Task 19 实测发现）。
     *
     * @param itemId         项目 id
     * @param itemCode       项目编码
     * @param itemName       项目名称
     * @param unitPrice      单价快照（分）
     * @param quantity       数量
     * @param amount         行金额（分）
     * @param selfExpenseOnly 无有效医保对照（仅自费，贯标硬校验提示）
     */
    public record QuoteLine(
            Long itemId,
            String itemCode,
            String itemName,
            Long unitPrice,
            BigDecimal quantity,
            Long amount,
            boolean selfExpenseOnly) {}
}

package com.fuyun.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 预计价请求（POST /pricing/quote，FU-M13-02，不落库；金额服务端按快照算）。
 *
 * @param patientId 患者主索引，非空
 * @param visitId   CF-3 就诊号，非空
 * @param lines     计价行清单，非空；来源：划价界面勾选
 */
public record QuoteRequest(
        @NotNull Long patientId,
        @NotNull String visitId,
        @NotEmpty @Valid List<Line> lines) {

    /**
     * 计价行。
     *
     * @param itemCode 项目编码
     * @param quantity 数量（>0）
     */
    public record Line(@NotNull String itemCode, @NotNull BigDecimal quantity) {}
}

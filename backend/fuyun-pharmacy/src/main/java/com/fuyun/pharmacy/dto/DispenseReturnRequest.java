package com.fuyun.pharmacy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 退药受理入参（POST /dispense-returns，Spec :171）。
 *
 * @param dispenseNo 调剂单号，必填
 * @param mode       受理模式：ISSUED_RETURN 发药后实物退 / DISPENSING_CANCEL 发药中明细退场，必填
 * @param items      逐行退药面（退药数/批号/追溯码），必填非空
 */
public record DispenseReturnRequest(
        @NotBlank String dispenseNo,
        @NotBlank String mode,
        @Valid @NotEmpty List<ReturnLine> items) {

    /**
     * 退药行。
     *
     * @param prescriptionItemId 处方明细 id（string 承载）
     * @param returnQuantity     退药数量（DECIMAL string），必填
     * @param traceCodes         追溯码集（ISSUED_RETURN 必填逐码核验；DISPENSING_CANCEL 可空）
     */
    public record ReturnLine(
            @NotNull String prescriptionItemId, @NotBlank String returnQuantity, List<String> traceCodes) {}
}

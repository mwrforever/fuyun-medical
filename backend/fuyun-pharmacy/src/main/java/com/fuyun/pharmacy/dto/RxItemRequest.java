package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 处方明细入参（quantity DECIMAL string 承载，D-18 同源）。
 *
 * @param drugId     药品 id，必填（drug 字典引用）
 * @param quantity   数量，必填且 >0（计费与发药共用口径）
 * @param unit       单位，可空（缺省取 drug.unit）
 * @param singleDose 单次剂量，可空
 * @param routeCode  给药途径 code（M01 字典），可空（药品登记途径集时必属集内）
 * @param frequency  用药频次 code（M01 字典），可空
 * @param days       用药天数，可空
 * @param usageNote  用法备注，可空
 */
public record RxItemRequest(
        @NotNull Long drugId,
        @NotBlank String quantity,
        String unit,
        String singleDose,
        String routeCode,
        String frequency,
        Integer days,
        String usageNote) {}

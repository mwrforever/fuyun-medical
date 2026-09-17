package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 健康档案项纠错请求（POST /health-items/{id}/correct；纠错不改原记录——旧行置 CORRECTED 新增回链行）。
 *
 * @param itemName  纠正后名称，非空
 * @param itemCode  纠正后字典 code（可空）
 * @param severity  纠正后严重程度（可空）
 * @param onsetDate 纠正后发生日期（可空）
 * @param note      纠错说明，非空（落新行 note，全程留痕）
 */
public record HealthItemCorrectRequest(
        @NotBlank @Size(max = 128) String itemName,
        @Size(max = 64) String itemCode,
        String severity,
        String onsetDate,
        @NotBlank @Size(max = 255) String note) {}

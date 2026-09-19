package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 处方作废入参（POST /prescriptions/{no}/cancel，Spec :169 必填原因）。
 *
 * @param reason 作废原因，必填（审计与 cancelled 事件留痕）
 */
public record PrescriptionCancelRequest(@NotBlank String reason) {}

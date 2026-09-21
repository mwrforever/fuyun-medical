package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 爽约限约手工解除请求（POST /appt-credits/{id}/release，Task 6）：解除理由必填留痕（审计抽查要素，
 * release_reason 列同源）。
 *
 * @param reason 解除理由，非空白；来源：管理员录入
 */
public record CreditReleaseRequest(@NotBlank String reason) {}

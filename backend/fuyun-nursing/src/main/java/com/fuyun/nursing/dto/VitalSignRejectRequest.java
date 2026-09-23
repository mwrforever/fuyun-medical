package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 体征复核驳回入参（POST /api/v1/nursing/vital-signs/{id}/reject）。驳回原因强制留痕
 * （落 vital_sign_record.remark，医疗审计依据）。
 *
 * @param reason 驳回原因，必填；来源：复核护士录入
 */
public record VitalSignRejectRequest(
        @NotBlank(message = "驳回原因不能为空") String reason) {}

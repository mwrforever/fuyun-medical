package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 不良事件处置退回入参（POST /api/v1/nursing/adverse-events/{no}/return）：
 * HANDLING→REPORTED 侧支迁移载体（独立 return 端点承载——brief 裁决），退回原因覆写
 * 处置记录（处置不充分需补充的留痕面）。
 *
 * @param reason      退回原因（≤2000 必填留痕），非空；来源：退回表单
 * @param returnerId  退回人员工 ID（必填——经 updated_by 审计列承载留痕），非空；来源：退回表单
 */
public record AdverseEventReturnRequest(
        @NotBlank(message = "退回原因必填（reason）") @Size(max = 2000, message = "退回原因超长（≤2000）")
        String reason,

        @NotNull(message = "退回人必填（returnerId）") Long returnerId) {}

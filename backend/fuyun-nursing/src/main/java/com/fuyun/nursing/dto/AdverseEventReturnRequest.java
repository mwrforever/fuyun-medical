package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 不良事件处置退回入参（POST /api/v1/nursing/adverse-events/{no}/return）：
 * HANDLING→REPORTED 侧支迁移载体（独立 return 端点承载——brief 裁决），退回原因覆写
 * 处置记录（处置不充分需补充的留痕面）。退回动作主体=登录令牌身份（W-72，2026-10-03
 * 裁决——returnerId 兼容保留忽略）。
 *
 * @param reason      退回原因（≤2000 必填留痕），非空；来源：退回表单
 * @param returnerId  退回人员工 ID（兼容保留——经 updated_by 审计列承载的留痕一律令牌身份，
 *                    本字段不再消费），可空；来源：退回表单
 */
public record AdverseEventReturnRequest(
        @NotBlank(message = "退回原因必填（reason）") @Size(max = 2000, message = "退回原因超长（≤2000）")
        String reason,

        Long returnerId) {}

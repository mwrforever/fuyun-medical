package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 执业授权停权请求体（POST /api/v1/system/practice/grants/{id}/withdraw，FU-M01-04）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。reason 为停权留痕必填项（审计与 warn 日志锚点），
 * 不入 practice_grant 列（状态迁移凭据归 audit_log）。
 *
 * @param reason 停权理由，非空 ≤255；来源：管理端停权表单（留痕依据）
 */
public record PracticeWithdrawRequest(
        @NotBlank @Size(max = 255) String reason) {}

package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 破码放行双授权入参（POST /api/v1/nursing/pda/override-check）：扫码核对失败后经双人授权
 * 放行（核对维度不通过但临床判定可执行的特殊面）。校验=两人不同 + 操作者角色 ∈
 * ward_config.override_roles（第二授权人角色面无 system 查询 api——以当前操作者角色近似
 * + 审计留痕降级，RBAC 完整面归 PR-4 W-37）；通过→override_flag=true + OVERRIDE 流水落行。
 *
 * @param executionNo          执行单号（≤32 必填），非空；来源：PDA 破码放行表单
 * @param primaryAuthorizerId  主授权人员工 ID（必填——流水 operator_id 落值），非空；来源：PDA 双人授权
 * @param secondaryAuthorizerId 第二授权人员工 ID（必填——与主授权人须不同，日志审计留痕），非空；
 *                             来源：PDA 双人授权
 * @param reason               放行理由（≤255 必填——流水 code_digest 摘要承载），非空；来源：PDA 表单
 */
public record OverrideCheckRequest(
        @NotBlank(message = "执行单号必填（executionNo）") @Size(max = 32, message = "执行单号超长（≤32）")
        String executionNo,

        @NotNull(message = "主授权人员工ID必填（primaryAuthorizerId）")
        Long primaryAuthorizerId,

        @NotNull(message = "第二授权人员工ID必填（secondaryAuthorizerId）")
        Long secondaryAuthorizerId,

        @NotBlank(message = "放行理由必填（reason）") @Size(max = 255, message = "放行理由超长（≤255）")
        String reason) {}

package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 破码放行双授权入参（POST /api/v1/nursing/pda/override-check）：扫码核对失败后经双人授权
 * 放行（核对维度不通过但临床判定可执行的特殊面）。校验=两人不同（W-72/A-4：服务端比较
 * 令牌身份 vs 第二授权人——请求体主授权人字段不参与）+ 操作者角色 ∈
 * ward_config.override_roles（第二授权人角色面无 system 查询 api——以当前操作者角色近似
 * + 审计留痕降级，RBAC 完整面归 PR-4 W-37）；通过→override_flag=true + OVERRIDE 流水落行
 * （流水 operator_id=令牌身份）。
 *
 * @param executionNo          执行单号（≤32 必填），非空；来源：PDA 破码放行表单
 * @param primaryAuthorizerId  主授权人员工 ID（兼容保留——服务端一律以令牌身份落值，W-72，
 *                             2026-10-03 裁决；本字段不再消费），可空；来源：PDA 双人授权
 * @param secondaryAuthorizerId 第二授权人员工 ID（必填——与令牌身份须不同，日志审计留痕），非空；
 *                             来源：PDA 双人授权
 * @param reason               放行理由（≤255 必填——流水 code_digest 摘要承载），非空；来源：PDA 表单
 */
public record OverrideCheckRequest(
        @NotBlank(message = "执行单号必填（executionNo）") @Size(max = 32, message = "执行单号超长（≤32）")
        String executionNo,

        Long primaryAuthorizerId,

        @NotNull(message = "第二授权人员工ID必填（secondaryAuthorizerId）")
        Long secondaryAuthorizerId,

        @NotBlank(message = "放行理由必填（reason）") @Size(max = 255, message = "放行理由超长（≤255）")
        String reason) {}

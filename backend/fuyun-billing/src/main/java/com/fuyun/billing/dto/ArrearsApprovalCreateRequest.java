package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 挂账审批单创建请求（POST /api/v1/billing/arrears-approvals）：零金额字段（GC18/金额红线——
 * 欠费额权威在 billing 聚合面，请求面仅定位键与理由）。
 *
 * @param visitId    CF-3 住院就诊号（I 型 14 位），必填；来源：出院预审 BLOCKED 的就诊行
 * @param applyReason 申请理由（欠费挂账缘由），必填 ≤255；来源：申请人录入
 */
public record ArrearsApprovalCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,

        @NotBlank(message = "applyReason 不能为空") @Size(max = 255, message = "applyReason 超长（≤255）")
        String applyReason) {}

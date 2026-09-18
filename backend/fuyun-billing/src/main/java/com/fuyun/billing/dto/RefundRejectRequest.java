package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 退费驳回请求（POST /refunds/{id}/reject，FU-M13-03；组件清单为 Task 18 IT 与 Task 19 前端唯一
 * 依据，禁改名改序）。REJECTED 为终态，驳回理由必填留痕（Spec §5 状态机注记）。
 *
 * @param reason 驳回理由，非空白（refund_request.reject_reason 留痕）；来源：审批人录入
 */
public record RefundRejectRequest(@NotBlank String reason) {}

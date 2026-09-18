package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医保补偿重试请求（POST /insurance/call-logs/{id}/compensation；组件清单禁改名改序）：
 * 补偿结论必填留痕（RefundRejectRequest 同款形态，审计检索键）。
 *
 * @param note 补偿结论（insurance_call_log.compensate_note 留痕，COMPENSATED 迁移结论可追溯）；来源：运维/收费组长录入
 */
public record CompensationRetryRequest(@NotBlank String note) {}

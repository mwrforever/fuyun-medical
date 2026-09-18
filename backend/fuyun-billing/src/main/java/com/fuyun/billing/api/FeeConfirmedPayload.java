package com.fuyun.billing.api;

/**
 * 费用确认入账事件载荷（billing.fee.confirmed，CF-4，V605 id 18；PENDING→CONFIRMED 迁移发布）。
 *
 * @param feeId     费用行 id
 * @param patientId 患者主索引
 * @param visitId   CF-3 就诊号
 * @param amount    金额（分）
 */
public record FeeConfirmedPayload(Long feeId, Long patientId, String visitId, Long amount) {}

package com.fuyun.billing.api;

/**
 * 押金账户变更事件载荷（billing.deposit.changed，CF-4，V605 id 21；欠费预警驱动）。
 *
 * @param accountId 押金账户 id
 * @param patientId 患者主索引
 * @param visitId   CF-3 住院就诊号
 * @param balance   变更后余额（分）
 * @param status    账户状态 NORMAL/ARREARS（阈值判定结果）
 */
public record DepositChangedPayload(Long accountId, Long patientId, String visitId, Long balance, String status) {}

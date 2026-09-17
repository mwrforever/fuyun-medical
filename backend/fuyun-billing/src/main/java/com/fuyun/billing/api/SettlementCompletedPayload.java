package com.fuyun.billing.api;

/**
 * 结算完成事件载荷（billing.settlement.completed，CF-4，V605 id 19；M03 订阅放行发药）。
 * 金额均为分；医保拆分仅摘要列，全量以结算单行为准。
 *
 * @param settlementId   结算单 id
 * @param settleNo       结算编号
 * @param patientId      患者主索引
 * @param visitId        CF-3 就诊号
 * @param settleType     结算类型 OUT/IN
 * @param payerType      支付类型 SELF_PAY/CITY_INS/…
 * @param totalAmount    应结总额（分）
 * @param pooledAmount   统筹支付（分）
 * @param acctPayAmount  个账支付（分）
 * @param selfPayAmount  自付（分）
 */
public record SettlementCompletedPayload(
        Long settlementId,
        String settleNo,
        Long patientId,
        String visitId,
        String settleType,
        String payerType,
        Long totalAmount,
        Long pooledAmount,
        Long acctPayAmount,
        Long selfPayAmount) {}

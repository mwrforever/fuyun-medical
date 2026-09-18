package com.fuyun.billing.api;

/**
 * 费用生成事件载荷（billing.fee.created，CF-4，V605 id 17）。
 *
 * @param feeId        费用行 id
 * @param feeNo        费用编号
 * @param patientId    患者主索引
 * @param visitId      CF-3 就诊号
 * @param chargeItemId 收费项目 id
 * @param itemName     项目名称快照
 * @param amount       金额（分，服务端计算）
 * @param chargeSource 计费来源（ChargeSource code）
 * @param billingKey   计费唯一键（消费方对账锚点）
 */
public record FeeCreatedPayload(
        Long feeId,
        String feeNo,
        Long patientId,
        String visitId,
        Long chargeItemId,
        String itemName,
        Long amount,
        String chargeSource,
        String billingKey) {}

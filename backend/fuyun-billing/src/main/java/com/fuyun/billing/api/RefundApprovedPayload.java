package com.fuyun.billing.api;

/**
 * 退费审批通过事件载荷（billing.refund.approved，CF-4，V605 id 20；免审直退同事件承载）。
 *
 * @param refundId     退费申请 id
 * @param refundNo     退费编号
 * @param settlementId 原结算单 id
 * @param patientId    患者主索引
 * @param amount       退费金额（分）
 * @param refundType   退费分级 DAY_CORRECTION/CROSS_DAY/SETTLED_REFUND
 * @param autoApproved 免审直退标识（审计抽查检索键）
 */
public record RefundApprovedPayload(
        Long refundId,
        String refundNo,
        Long settlementId,
        Long patientId,
        Long amount,
        String refundType,
        boolean autoApproved) {}

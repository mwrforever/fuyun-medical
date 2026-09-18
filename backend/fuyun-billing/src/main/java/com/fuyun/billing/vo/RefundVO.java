package com.fuyun.billing.vo;

import com.fuyun.billing.entity.RefundRequest;

/**
 * 退费出参（POST /refunds / GET /refunds / 审批·执行回显；组件清单为 Task 18 IT 与 Task 19 前端
 * 唯一依据，禁改名改序）：枚举出 code 字符串、金额/id 一律 Long 包装出网（统一契约口径）经
 * Jackson→string；approver/autoApproved 免审直退行审批人留空、标识置 true（审计抽查检索键）。
 *
 * @param id           退费申请 id
 * @param refundNo     退费编号（R+服务端生成序列号）
 * @param settlementId 原结算单 id
 * @param patientId    患者主索引
 * @param visitId      CF-3 就诊号
 * @param refundType   退费分级 DAY_CORRECTION/CROSS_DAY/SETTLED_REFUND
 * @param amount       退费金额（分，服务端按明细聚合）
 * @param reason       退费理由
 * @param applicant    申请人（登录身份注入）
 * @param approver     审批人（终批审批人；双人守卫：≠applicant 且二级批时≠一级审批人；待审批/驳回行为 null）
 * @param autoApproved 免审直退标识（审计抽查检索键）
 * @param status       退费状态 DRAFT/PENDING_APPROVAL/PENDING_SECOND_APPROVAL/APPROVED/EXECUTED/REJECTED
 *                     （PENDING_SECOND_APPROVAL=一级已批待二级，前端按 status 值区分待一级/待二级；不加字段）
 */
public record RefundVO(
        Long id,
        String refundNo,
        Long settlementId,
        Long patientId,
        String visitId,
        String refundType,
        Long amount,
        String reason,
        String applicant,
        String approver,
        Boolean autoApproved,
        String status) {

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出；金额为关键业务字段禁 MapStruct 手写映射）。
     *
     * @param refund 退费申请实体，非空；来源：apply/page 事务内查询结果
     * @return 出参 VO，非空；枚举列转 code 字符串
     */
    public static RefundVO from(RefundRequest refund) {
        return new RefundVO(
                refund.getId(),
                refund.getRefundNo(),
                refund.getSettlementId(),
                refund.getPatientId(),
                refund.getVisitId(),
                refund.getRefundType() == null ? null : refund.getRefundType().getCode(),
                refund.getAmount(),
                refund.getReason(),
                refund.getApplicant(),
                refund.getApprover(),
                refund.getAutoApproved(),
                refund.getStatus() == null ? null : refund.getStatus().getCode());
    }
}

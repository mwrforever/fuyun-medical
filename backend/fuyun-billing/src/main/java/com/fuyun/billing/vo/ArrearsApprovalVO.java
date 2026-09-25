package com.fuyun.billing.vo;

import com.fuyun.billing.entity.ArrearsApproval;
import com.fuyun.billing.enums.ArrearsApprovalStatus;

/**
 * 挂账审批单视图（创建/通过/驳回共用回显；金额仅 approvedBalance 快照只出不进，GC18 口径；
 * 时间类字段不出网——ReviewTaskVO 先例，决定时间经审计面留痕）。
 *
 * @param approvalNo      审批单号（业务唯一）
 * @param visitId         CF-3 住院就诊号
 * @param applyReason     申请理由
 * @param approver        审批人（决定后回填，待审批为 null）
 * @param approvedBalance 批准时点押金余额快照（分；APPROVED 必填，余态 null）
 * @param status          审批状态（ArrearsApprovalStatus 四态 code）
 */
public record ArrearsApprovalVO(
        String approvalNo, String visitId, String applyReason, String approver, Long approvedBalance, String status) {

    /**
     * 实体投影（controller 出参统一装配点；status 经枚举 code 出网，禁实体直出）。
     *
     * @param approval 审批单实体，非空；来源：服务层事务内读回
     * @return 审批单视图，非空
     */
    public static ArrearsApprovalVO from(ArrearsApproval approval) {
        return new ArrearsApprovalVO(
                approval.getApprovalNo(),
                approval.getVisitId(),
                approval.getApplyReason(),
                approval.getApprover(),
                approval.getApprovedBalance(),
                approval.getStatus() == null ? null : approval.getStatus().getCode());
    }

    /**
     * 决出态静态视图（CAS 成功后的出参装配，免二次查询读回；状态由决出动作权威给定）。
     *
     * @param approval CAS 前读回的审批单实体（单号/就诊/理由权威），非空
     * @param approver 审批人（操作者上下文），非空
     * @param status   决出后状态（APPROVED/REJECTED），非空
     * @param balance  押金余额快照（APPROVED 必填，REJECTED null），可空
     * @return 审批单视图，非空
     */
    public static ArrearsApprovalVO decided(
            ArrearsApproval approval, String approver, ArrearsApprovalStatus status, Long balance) {
        return new ArrearsApprovalVO(
                approval.getApprovalNo(),
                approval.getVisitId(),
                approval.getApplyReason(),
                approver,
                balance,
                status.getCode());
    }
}

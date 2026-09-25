package com.fuyun.billing.service;

import com.fuyun.billing.dto.ArrearsApprovalCreateRequest;
import com.fuyun.billing.vo.ArrearsApprovalVO;

/**
 * 挂账审批服务（billing.arrears_approval 唯一业务面，Spec M-10 四态；P2 PR-1 Task 13）：
 * 出院欠费挂账单创建（DRAFT→PENDING_APPROVAL 内联瞬时）与决出（approve 发布
 * billing.arrears.approved / reject 终态留痕）。事务边界归实现；审批人恒取登录上下文（红线 3，
 * 禁前端传人）；状态迁移唯一经 ArrearsApprovalMapper.casDecide 条件更新。
 */
public interface IArrearsApprovalService {

    /**
     * 创建挂账审批单（POST /arrears-approvals，DRAFT→PENDING_APPROVAL，关联 visitId）：
     * 审批单号服务端生成（AR+序列号），落库即待审批态（DRAFT 为词表保完整瞬时态）。
     *
     * @param req 创建请求（visitId+申请理由），非空；来源：收费工作站挂账审批发起
     * @return 审批单视图（含单号与待审批态），非空
     */
    ArrearsApprovalVO create(ArrearsApprovalCreateRequest req);

    /**
     * 审批通过（POST /arrears-approvals/{no}/approve，PENDING_APPROVAL→APPROVED）：同语句落
     * 审批人/决定时间/押金余额快照，同事务发布 billing.arrears.approved（id 73 载荷四字段，
     * AFTER_COMMIT 出 fy.topic）。
     *
     * @param approvalNo 审批单号，非空；来源：路径参数
     * @return 审批单视图（APPROVED 态），非空
     * @throws com.fuyun.common.exception.BizException BILL-1032（404 单号不存在）/
     *                 BILL-1033（409 非待审批态或并发被抢）/ BILL-1012（400 缺审批操作者上下文——
     *                 语义扩展先例同 BILL-1023，资金审批缺审计要素不落不可追放行）
     */
    ArrearsApprovalVO approve(String approvalNo);

    /**
     * 审批驳回（POST /arrears-approvals/{no}/reject，PENDING_APPROVAL→REJECTED）：终态留痕
     * 不发事件（登记面无驳回契约，欠费须另行结清或重新申请）。
     *
     * @param approvalNo 审批单号，非空；来源：路径参数
     * @return 审批单视图（REJECTED 态），非空
     * @throws com.fuyun.common.exception.BizException 同 {@link #approve}
     */
    ArrearsApprovalVO reject(String approvalNo);
}

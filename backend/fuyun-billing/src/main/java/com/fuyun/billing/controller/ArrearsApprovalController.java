package com.fuyun.billing.controller;

import com.fuyun.billing.dto.ArrearsApprovalCreateRequest;
import com.fuyun.billing.service.IArrearsApprovalService;
import com.fuyun.billing.vo.ArrearsApprovalVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 挂账审批端点（M13 住院计费联动，P2 PR-1 Task 13）：出院欠费挂账单创建/通过/驳回三动作。
 *
 * <p>资金审批权限注记（W-37）：本 PR 端点在位，权限全量收敛归 PR-4（计划 GC13——
 * W-37~W-41/W-47 安全收敛顺延）。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：状态机/快照/事件发布全归服务层；三端点全挂
 * WRITE 审计（GC22——挂账审批属资金类写动作，@AuditLog + AuditLogAspect 上下文内落库）。
 */
@Tag(name = "billing-arrears-approvals", description = "M13 住院欠费挂账审批（创建/通过/驳回）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class ArrearsApprovalController {

    private final IArrearsApprovalService arrearsApprovalService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param arrearsApprovalService 挂账审批服务（创建/决出业务面），非空
     */
    public ArrearsApprovalController(IArrearsApprovalService arrearsApprovalService) {
        this.arrearsApprovalService = arrearsApprovalService;
    }

    /**
     * 创建挂账审批单（POST /arrears-approvals，DRAFT→PENDING_APPROVAL，关联 visitId；WRITE 审计）。
     *
     * @param req 创建请求（@Valid 声明式校验）；来源：收费工作站挂账审批发起
     * @return 审批单视图（含服务端生成单号）；经全局序列化出网
     * @throws com.fuyun.common.exception.BizException 入参校验失败由全局 400 承载
     */
    @Operation(summary = "创建住院欠费挂账审批单", operationId = "createArrearsApproval")
    @PostMapping("/arrears-approvals")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ArrearsApprovalVO create(@Valid @RequestBody ArrearsApprovalCreateRequest req) {
        return arrearsApprovalService.create(req);
    }

    /**
     * 审批通过（POST /arrears-approvals/{no}/approve，PENDING_APPROVAL→APPROVED + 发布
     * billing.arrears.approved；WRITE 审计）。
     *
     * @param approvalNo 审批单号；来源：路径参数
     * @return 审批单视图（APPROVED 态 + 押金余额快照）；经全局序列化出网
     * @throws com.fuyun.common.exception.BizException BILL-1032（404）/ BILL-1033（409）/
     *                 BILL-1012（400 缺审批人上下文）
     */
    @Operation(summary = "挂账审批通过（发布 arrears.approved 放行）", operationId = "approveArrearsApproval")
    @PostMapping("/arrears-approvals/{approvalNo}/approve")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ArrearsApprovalVO approve(@PathVariable("approvalNo") String approvalNo) {
        return arrearsApprovalService.approve(approvalNo);
    }

    /**
     * 审批驳回（POST /arrears-approvals/{no}/reject，PENDING_APPROVAL→REJECTED 终态留痕；
     * WRITE 审计；不发事件）。
     *
     * @param approvalNo 审批单号；来源：路径参数
     * @return 审批单视图（REJECTED 态）；经全局序列化出网
     * @throws com.fuyun.common.exception.BizException 同 approve
     */
    @Operation(summary = "挂账审批驳回（终态留痕）", operationId = "rejectArrearsApproval")
    @PostMapping("/arrears-approvals/{approvalNo}/reject")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ArrearsApprovalVO reject(@PathVariable("approvalNo") String approvalNo) {
        return arrearsApprovalService.reject(approvalNo);
    }
}

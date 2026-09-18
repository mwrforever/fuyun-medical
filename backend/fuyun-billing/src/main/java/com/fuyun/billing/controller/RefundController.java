package com.fuyun.billing.controller;

import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.dto.RefundRejectRequest;
import com.fuyun.billing.entity.RefundRequest;
import com.fuyun.billing.enums.RefundStatus;
import com.fuyun.billing.service.IRefundService;
import com.fuyun.billing.vo.RefundVO;
import com.fuyun.common.web.PageResult;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退费端点（FU-M13-03 退侧）：退费申请、双人守卫审批、驳回、执行原路退回与审批列表分页查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：分级判定/双人守卫/占用硬前置/台账记账/事件发布全归
 * 服务层；写端点挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-refunds", description = "M13 退费（申请/审批/驳回/执行退回/审批列表）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class RefundController {

    private final IRefundService refundService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param refundService 退费服务（申请/审批/驳回/执行/分页），非空
     */
    public RefundController(IRefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * 退费申请（POST /refunds，免审阈值内当日未占用直退 APPROVED、否则 PENDING_APPROVAL；WRITE 审计）。
     *
     * @param req 退费申请请求（@Valid 声明校验，行级级联且 refundQuantity @Positive 恒正；金额服务端
     *            按明细算）；来源：收费员工作站
     * @return 新退费申请 id（免审直退行已 APPROVED，可携 id 直达执行入口）；201
     * @throws com.fuyun.common.exception.BizException BILL-1012（400 缺登录上下文）/
     *                 BILL-1014（404 缺原结算单）/ BILL-1010（404 费用行缺行）/
     *                 BILL-1017（409 执行占用硬前置）/ BILL-1021（409 超可退余额）
     */
    @Operation(summary = "退费申请（免审阈值内当日未占用直退）", operationId = "applyRefund")
    @PostMapping("/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public long apply(@Valid @RequestBody RefundApplyRequest req) {
        return refundService.apply(req);
    }

    /**
     * 退费审批（POST /refunds/{id}/approve，双人守卫：审批人≠申请人 BILL-1020；WRITE 审计）。
     *
     * @param id 退费申请 id（路径变量）
     * @return 204 无体（状态机迁移 PENDING_APPROVAL→APPROVED）
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非 PENDING_APPROVAL）/ BILL-1020（403 自审）
     */
    @Operation(summary = "退费审批（双人守卫）", operationId = "approveRefund")
    @PostMapping("/refunds/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void approve(@PathVariable long id) {
        refundService.approve(id);
    }

    /**
     * 退费驳回（POST /refunds/{id}/reject，PENDING_APPROVAL→REJECTED 终态留痕；WRITE 审计）。
     *
     * @param id  退费申请 id（路径变量）
     * @param req 驳回请求（@Valid，理由必填）；来源：审批人录入
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非 PENDING_APPROVAL）
     */
    @Operation(summary = "退费驳回（终态留痕）", operationId = "rejectRefund")
    @PostMapping("/refunds/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void reject(@PathVariable long id, @Valid @RequestBody RefundRejectRequest req) {
        refundService.reject(id, req.reason());
    }

    /**
     * 退费执行（POST /refunds/{id}/execute，原路退回：CARD_BALANCE 行台账 REFUND 入账、费用行
     * PART/FULL_REFUND、结算单全额退转 REFUNDED；WRITE 审计）。
     *
     * @param id 退费申请 id（路径变量）
     * @return 204 无体（状态机迁移 APPROVED→EXECUTED）
     * @throws com.fuyun.common.exception.BizException BILL-1018（404 缺单）/
     *                 BILL-1019（409 非 APPROVED）；PAT-1013/1014（卡记账失败回滚上抛）
     */
    @Operation(summary = "退费执行（原路退回）", operationId = "executeRefund")
    @PostMapping("/refunds/{id}/execute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void execute(@PathVariable long id) {
        refundService.execute(id);
    }

    /**
     * 退费列表（GET /refunds?status=&page=&size=，审批工作台分页，status 可空=全部，id 升序）。
     *
     * @param status 退费状态过滤，可空；来源：审批列表筛选条件
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 退费申请分页出参；200
     */
    @Operation(summary = "退费申请分页查询", operationId = "listRefunds")
    @GetMapping("/refunds")
    public PageResult<RefundVO> list(
            @RequestParam(value = "status", required = false) RefundStatus status,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        // 实体禁直出：查询结果逐行经静态工厂转 VO（出网边界唯一出口）
        PageResult<RefundRequest> result = refundService.page(status, page, size);
        return PageResult.of(
                result.content().stream().map(RefundVO::from).toList(), result.page(), result.size(), result.total());
    }
}

package com.fuyun.billing.controller;

import com.fuyun.billing.dto.SettleRequest;
import com.fuyun.billing.dto.SettlementPreviewRequest;
import com.fuyun.billing.service.ISettlementService;
import com.fuyun.billing.vo.SettlementPreviewVO;
import com.fuyun.billing.vo.SettlementVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算端点（FU-M13-03）：预结算划价、正式结算收银与结算单查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：金额勾稽/状态机/就诊卡记账/事件发布全归服务层，
 * controller 直传 {@code SettleRequest}（settleNo+payments）不拆参；写端点挂 WRITE 审计
 * （@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-settlements", description = "M13 结算（预结算/正式结算/结算单查询）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class SettlementController {

    private final ISettlementService settlementService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param settlementService 结算服务（预结算/正式结算/结算查询），非空
     */
    public SettlementController(ISettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 预结算（POST /settlements/preview，划价收款依据，落 DRAFT/PRESETTLED 结算单；WRITE 审计）。
     *
     * @param req 预结算请求（@Valid 声明式校验）；来源：收费员工作站划价确认
     * @return 结算单草稿出参（金额分）；200
     * @throws com.fuyun.common.exception.BizException BILL-1008（409 无待结算费用）/
     *                 BILL-1006（409 未贯标费用行）/
     *                 BILL-1016（409 医保五拆分勾稽不平）/
     *                 BILL-1024（502 医保通道业务失败）
     */
    @Operation(summary = "预结算（划价收款依据）", operationId = "previewSettlement")
    @PostMapping("/settlements/preview")
    @AuditLog(actionType = AuditActionType.WRITE)
    public SettlementPreviewVO preview(@Valid @RequestBody SettlementPreviewRequest req) {
        return settlementService.preview(req);
    }

    /**
     * 正式结算（POST /settlements，金额两层勾稽+就诊卡记账+settlement.completed 事件，幂等以
     * settleNo 终态为锚点；WRITE 审计）。
     *
     * @param req 结算请求（@Valid 声明式校验，payments 行级级联校验且行金额 @Positive 恒正）；
     *            来源：收银台确认
     * @return 结算出参（SETTLED 终态，重放同 settleNo 直返）；200
     * @throws com.fuyun.common.exception.BizException BILL-1014（404 缺单）/ BILL-1015（409 状态不允许）/
     *                 BILL-1016（409 勾稽不平）/ BILL-1012（400 卡账户引用缺失/非法/多卡混付）
     */
    @Operation(summary = "正式结算（幂等以 settleNo 终态为锚点）", operationId = "settleSettlement")
    @PostMapping("/settlements")
    @AuditLog(actionType = AuditActionType.WRITE)
    public SettlementVO settle(@Valid @RequestBody SettleRequest req) {
        return settlementService.settle(req);
    }

    /**
     * 结算单查询（GET /settlements/{no}，按结算编号查结算回执；纯读）。
     *
     * @param no 结算编号（路径变量）；来源：收银后回显/前端路由参数
     * @return 结算出参；200
     * @throws com.fuyun.common.exception.BizException BILL-1014（404 结算单不存在）
     */
    @Operation(summary = "按结算编号查结算单", operationId = "getSettlementByNo")
    @GetMapping("/settlements/{no}")
    public SettlementVO getByNo(@PathVariable String no) {
        return settlementService.getByNo(no);
    }
}

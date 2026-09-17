package com.fuyun.billing.controller;

import com.fuyun.billing.dto.ManualChargeRequest;
import com.fuyun.billing.dto.QuoteRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.vo.FeeRecordVO;
import com.fuyun.billing.vo.QuoteVO;
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
 * 划价与费用端点（FU-M13-02）：预计价（不落库）、手工计费、费用查询与未结算作废。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：命令组装与操作者上下文注入收敛在服务薄编排层
 * （manualCharge），controller 不自行组装 FeeGenerateCommand；写端点挂 WRITE 审计
 * （@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-fees", description = "M13 划价与费用（预计价/手工计费/费用查询/未结算作废）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class FeeController {

    private final IPricingEngineService pricingEngineService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param pricingEngineService 计价引擎（生成/查询/预计价/作废），非空
     */
    public FeeController(IPricingEngineService pricingEngineService) {
        this.pricingEngineService = pricingEngineService;
    }

    /**
     * 预计价（POST /pricing/quote，只算不落、不发消息，供开单界面与收费处划价展示）。
     *
     * @param req 预计价请求（@Valid 声明式校验）；来源：划价界面勾选
     * @return 预计价结果（金额分，无对照行显式标仅自费）；200
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 项目缺行/停用）/
     *                 BILL-1008（409 无生效价或组合未维护构成）
     */
    @Operation(summary = "预计价（不落库）", operationId = "quotePricing")
    @PostMapping("/pricing/quote")
    public QuoteVO quote(@Valid @RequestBody QuoteRequest req) {
        return pricingEngineService.quote(req);
    }

    /**
     * 手工计费（POST /fees/manual，操作者由服务层从登录上下文注入、不由前端传——红线 3；WRITE 审计）。
     *
     * @param req 手工计费请求（@Valid 声明式校验，理由必填）；来源：收费员工作站补录
     * @return 新费用行 id（组合展开时为首成员行 id）；201
     * @throws com.fuyun.common.exception.BizException BILL-1012（400 缺登录上下文/审计要素）/
     *                 BILL-1009（409 重复计费）/ BILL-1008（409 定价不可得）
     */
    @Operation(summary = "手工计费", operationId = "manualChargeFee")
    @PostMapping("/fees/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public long manual(@Valid @RequestBody ManualChargeRequest req) {
        return pricingEngineService.manualCharge(req);
    }

    /**
     * 费用查询（GET /fees?visitId=，按就诊号全状态分页，id 升序=事件行序）。
     *
     * @param visitId CF-3 就诊号，非空；来源：工作站费用查询输入
     * @param page    页码（0 基），缺省 0
     * @param size    单页条数（1-200），缺省 20
     * @return 费用行分页出参；200
     */
    @Operation(summary = "就诊费用分页查询", operationId = "listFees")
    @GetMapping("/fees")
    public PageResult<FeeRecordVO> listByVisit(
            @RequestParam String visitId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        // 实体禁直出：查询结果逐行经静态工厂转 VO（出网边界唯一出口）
        PageResult<FeeRecord> result = pricingEngineService.pageByVisit(visitId, page, size);
        return PageResult.of(
                result.content().stream().map(FeeRecordVO::from).toList(),
                result.page(),
                result.size(),
                result.total());
    }

    /**
     * 未结算作废（POST /fees/{id}/cancel，PENDING/CONFIRMED→CANCELLED，行保留释放 billing_key；
     * 理由经 WRITE 审计留痕，fee_record 不另建立由列）。
     *
     * @param id     费用行 id（路径变量）
     * @param reason 作废理由，非空；来源：操作者录入
     * @return 204 无体（状态机变更）
     * @throws com.fuyun.common.exception.BizException BILL-1010（404 费用不存在）/
     *                 BILL-1011（409 非 PENDING/CONFIRMED 态或状态竞态）
     */
    @Operation(summary = "未结算费用作废", operationId = "cancelFee")
    @PostMapping("/fees/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void cancel(@PathVariable long id, @RequestParam String reason) {
        pricingEngineService.cancel(id, reason);
    }
}

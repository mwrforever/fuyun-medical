package com.fuyun.billing.controller;

import com.fuyun.billing.dto.PriceDraftRequest;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.billing.vo.ChargeItemPriceVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调价端点（FU-M13-01 管理面，方案 3.4）：调价草稿落库、发布生效（闭旧区间 + 广播）与版本链查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界与广播发布点在 service impl 方法级；
 * 写端点挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-price-adjustments", description = "M13 调价管理（价格版本化 + 生效广播）")
@RestController
@RequestMapping("/api/v1/billing")
public class PriceAdjustmentController {

    private final IChargePriceService chargePriceService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param chargePriceService 价格版本化服务（草稿/发布/快照/版本链），非空
     */
    public PriceAdjustmentController(IChargePriceService chargePriceService) {
        this.chargePriceService = chargePriceService;
    }

    /**
     * 新建调价草稿（POST /charge-items/{id}/prices，版本号项目内自增、落 DRAFT 不生效；WRITE 审计）。
     *
     * @param id  收费项目 id（路径变量，仅资源定位；归属校验以请求体 itemCode 按码取项为准）
     * @param req 草稿请求（@Valid 声明式校验，itemCode 必填——saveDraft 按码取项目）；来源：物价员录入
     * @return 草稿行 id（发布端点入参）；201
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 项目不存在）/ BILL-1003（409 停用）
     */
    @Operation(summary = "新建调价草稿", operationId = "createPriceDraft")
    @PostMapping("/charge-items/{id}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public long createDraft(@PathVariable long id, @Valid @RequestBody PriceDraftRequest req) {
        return chargePriceService.saveDraft(req);
    }

    /**
     * 发布调价（POST /price-adjustments/{id}/publish，DRAFT→PUBLISHED、闭旧区间、广播工作站；WRITE 审计）。
     *
     * @param id 价格版本行 id（路径变量）
     * @return 204 无体（状态机变更，广播 AFTER_COMMIT 出 MQ）
     * @throws com.fuyun.common.exception.BizException BILL-1005（404 缺行）/ BILL-1028（409 非 DRAFT 重复发布）
     */
    @Operation(summary = "发布调价（DRAFT→PUBLISHED）", operationId = "publishPriceAdjustment")
    @PostMapping("/price-adjustments/{id}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void publish(@PathVariable long id) {
        chargePriceService.publish(id);
    }

    /**
     * 列项目价格版本链（GET /charge-items/{id}/prices，版本历史追溯，version 倒序）。
     *
     * @param id 收费项目 id（路径变量）
     * @return 版本清单出参（含 DRAFT/EXPIRED 全状态），可为空清单；200
     */
    @Operation(summary = "列项目价格版本链", operationId = "listChargeItemPrices")
    @GetMapping("/charge-items/{id}/prices")
    public List<ChargeItemPriceVO> listVersions(@PathVariable long id) {
        // 实体禁直出：查询结果经静态工厂逐行转 VO（出网边界唯一出口）
        return chargePriceService.listVersions(id).stream()
                .map(ChargeItemPriceVO::from)
                .toList();
    }
}

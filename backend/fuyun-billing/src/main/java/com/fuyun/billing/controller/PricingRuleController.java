package com.fuyun.billing.controller;

import com.fuyun.billing.dto.PricingRuleUpsertRequest;
import com.fuyun.billing.service.IPricingRuleService;
import com.fuyun.billing.vo.PricingRuleVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计价规则端点（FU-M13-02 规则配置面）：规则登记与全量清单查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界在 service impl 方法级；登记端点挂
 * WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-pricing-rules", description = "M13 计价规则管理（触发型分流配置面）")
@RestController
@RequestMapping("/api/v1/billing")
public class PricingRuleController {

    private final IPricingRuleService pricingRuleService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param pricingRuleService 计价规则服务（JSON 守卫/upsert/清单），非空
     */
    public PricingRuleController(IPricingRuleService pricingRuleService) {
        this.pricingRuleService = pricingRuleService;
    }

    /**
     * 规则登记（POST /pricing-rules/upsert，ruleCode 业务唯一 upsert；WRITE 审计）。
     *
     * @param req 规则登记请求（@Valid 声明式校验；item_scope JSON 合法性由 service 落库前守卫）
     * @return 落库规则行 id（改写为原行 id，新插为回填雪花 id）；200
     * @throws com.fuyun.common.exception.BizException BILL-1027（400 item_scope 非法 JSON）
     */
    @Operation(summary = "计价规则登记（ruleCode upsert）", operationId = "upsertPricingRule")
    @PostMapping("/pricing-rules/upsert")
    @AuditLog(actionType = AuditActionType.WRITE)
    public long upsert(@Valid @RequestBody PricingRuleUpsertRequest req) {
        return pricingRuleService.upsert(req);
    }

    /**
     * 全量规则清单（GET /pricing-rules，配置面列表页）。
     *
     * @return 规则出参清单；200
     */
    @Operation(summary = "全量规则清单", operationId = "listPricingRules")
    @GetMapping("/pricing-rules")
    public List<PricingRuleVO> listAll() {
        // 实体禁直出：清单逐行经静态工厂转 VO（出网边界唯一出口）
        return pricingRuleService.listAll().stream().map(PricingRuleVO::from).toList();
    }
}

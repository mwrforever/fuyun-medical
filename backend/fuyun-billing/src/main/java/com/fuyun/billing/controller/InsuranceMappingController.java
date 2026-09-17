package com.fuyun.billing.controller;

import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.service.IInsuranceMappingService;
import com.fuyun.billing.vo.InsuranceMappingVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医保对照端点（FU-M13-01 贯标管理面）：项目级 22 项编码对照的登记与生效行查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界在 service impl 方法级；登记端点挂
 * WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-insurance-mappings", description = "M13 医保对照管理（国家 22 项编码贯标）")
@RestController
@RequestMapping("/api/v1/billing")
public class InsuranceMappingController {

    private final IInsuranceMappingService insuranceMappingService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param insuranceMappingService 医保对照服务（单 ACTIVE 行 upsert/生效查询），非空
     */
    public InsuranceMappingController(IInsuranceMappingService insuranceMappingService) {
        this.insuranceMappingService = insuranceMappingService;
    }

    /**
     * 对照登记（POST /insurance-mappings/upsert，存在 ACTIVE 行则改、无则插；WRITE 审计）。
     *
     * @param req 对照登记请求（@Valid 声明式校验）；来源：物价员对照国家目录录入
     * @return 落库对照行 id（改写为原行 id，新插为回填雪花 id）；200
     */
    @Operation(summary = "医保对照登记（单 ACTIVE 行 upsert）", operationId = "upsertInsuranceMapping")
    @PostMapping("/insurance-mappings/upsert")
    @AuditLog(actionType = AuditActionType.WRITE)
    public long upsert(@Valid @RequestBody InsuranceMappingUpsertRequest req) {
        return insuranceMappingService.upsert(req);
    }

    /**
     * 按项目查当前 ACTIVE 对照（GET /insurance-mappings/{chargeItemId}，贯标状态查询）。
     *
     * @param chargeItemId 收费项目 id（路径变量）
     * @return 生效对照出参；无生效对照时 204 无体（未贯标属正常态，非错误）
     */
    @Operation(summary = "按项目查当前 ACTIVE 对照", operationId = "getEffectiveInsuranceMapping")
    @GetMapping("/insurance-mappings/{chargeItemId}")
    public ResponseEntity<InsuranceMappingVO> effective(@PathVariable long chargeItemId) {
        InsuranceMapping mapping = insuranceMappingService.effectiveMapping(chargeItemId);
        // 未贯标/对照已失效返回 204：贯标硬校验（BILL-1006）归结算 preview 分支，查询态不报错
        return mapping == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(InsuranceMappingVO.from(mapping));
    }
}

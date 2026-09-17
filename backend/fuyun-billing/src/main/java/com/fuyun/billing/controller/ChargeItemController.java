package com.fuyun.billing.controller;

import com.fuyun.billing.dto.ChargeItemCreateRequest;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.vo.ChargeItemVO;
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
 * 收费项目端点（FU-M13-01 管理面）：项目建档、按码查生效项与组合构成维护。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界在 service impl 方法级；写端点挂 WRITE
 * 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-charge-items", description = "M13 收费项目管理（物价项目库权威源）")
@RestController
@RequestMapping("/api/v1/billing")
public class ChargeItemController {

    private final IChargeItemService chargeItemService;

    /**
     * 构造器注入（A.1-7），装配归 BillingWebConfig @Import。
     *
     * @param chargeItemService 收费项目服务（建档/守卫/组合构成），非空
     */
    public ChargeItemController(IChargeItemService chargeItemService) {
        this.chargeItemService = chargeItemService;
    }

    /**
     * 新建收费项目（POST /charge-items，默认 SINGLE/ACTIVE；WRITE 审计）。
     *
     * @param req 新建请求（@Valid 声明式校验）；来源：物价员录入
     * @return 新建项目出参；201
     * @throws com.fuyun.common.exception.BizException BILL-1002（409 编码占用）
     */
    @Operation(summary = "新建收费项目", operationId = "createChargeItem")
    @PostMapping("/charge-items")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public ChargeItemVO create(@Valid @RequestBody ChargeItemCreateRequest req) {
        long id = chargeItemService.createChargeItem(req);
        // 实体禁直出：落库后按 id 回读经静态工厂转 VO（出网边界唯一出口）
        return ChargeItemVO.from(chargeItemService.getById(id));
    }

    /**
     * 按编码查生效项目（GET /charge-items/by-code/{itemCode}，管理面查询与计价联调用）。
     *
     * @param itemCode 院内物价编码（路径变量）
     * @return 生效项目出参；200
     * @throws com.fuyun.common.exception.BizException BILL-1001（404 缺项）/ BILL-1003（409 停用）
     */
    @Operation(summary = "按编码查生效项目", operationId = "getActiveChargeItem")
    @GetMapping("/charge-items/by-code/{itemCode}")
    public ChargeItemVO getByCode(@PathVariable String itemCode) {
        return ChargeItemVO.from(chargeItemService.requireActiveByCode(itemCode));
    }

    /**
     * 组合构成维护（POST /charge-items/{id}/combo-components，全量覆盖式落成员；WRITE 审计）。
     *
     * @param id         组合项目 id（路径变量）
     * @param components 成员清单（@Valid 声明式校验）；来源：物价员维护
     * @return 204 无体
     * @throws com.fuyun.common.exception.BizException BILL-1003（409 非组合项目）
     */
    @Operation(summary = "组合构成全量覆盖维护", operationId = "saveComboComponents")
    @PostMapping("/charge-items/{id}/combo-components")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void saveComboComponents(
            @PathVariable long id, @Valid @RequestBody List<@Valid ComboComponentRequest> components) {
        chargeItemService.saveComboComponents(id, components);
    }
}

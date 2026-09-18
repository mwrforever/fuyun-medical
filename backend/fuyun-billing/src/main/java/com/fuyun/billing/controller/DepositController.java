package com.fuyun.billing.controller;

import com.fuyun.billing.dto.DepositRequest;
import com.fuyun.billing.service.IDepositService;
import com.fuyun.billing.vo.DepositAccountVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 押金端点（FU-M13-04）：住院预交金缴存与账户查询。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：原子记账/欠费阈值判定/事件发布全归服务层，
 * controller 直传 {@code DepositRequest} 不拆参；缴存写端点挂 WRITE 审计（@AuditLog 注解 +
 * AuditLogAspect 上下文内拦截落 system.audit_log）。
 */
@Tag(name = "billing-deposits", description = "M13 住院预交金（缴存/账户查询）")
@RestController
@RequestMapping("/api/v1/billing")
@Validated
public class DepositController {

    private final IDepositService depositService;

    /**
     * 构造器注入（A.1-7），装配归 fuyun-app 侧 config @Import（Task 16 交付）。
     *
     * @param depositService 押金服务（缴存/账户查询），非空
     */
    public DepositController(IDepositService depositService) {
        this.depositService = depositService;
    }

    /**
     * 住院预交金缴存（POST /deposits，原子记账+欠费预警判定+deposit.changed 事件；WRITE 审计）。
     *
     * @param req 缴存请求（@Valid 声明式校验）；来源：收费窗口/自助机/扫码/线上渠道
     * @return 新缴存流水 id（经全局 Long→string 序列化出网）
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）/
     *                 BILL-1012（400 缺操作者上下文/支付方式混入结算域值）/
     *                 BILL-1023（409 账户终态拒缴存/并发回读落空）
     */
    @Operation(summary = "住院预交金缴存（原子记账+欠费预警判定）", operationId = "createDeposit")
    @PostMapping("/deposits")
    @AuditLog(actionType = AuditActionType.WRITE)
    public long deposit(@Valid @RequestBody DepositRequest req) {
        return depositService.deposit(req);
    }

    /**
     * 按就诊号查押金账户（GET /deposits?visitId=，余额/阈值/状态查询；纯读）。
     *
     * @param visitId CF-3 住院就诊号（查询参数）；来源：M04 工作站/患者端路由参数
     * @return 账户出参；200
     * @throws com.fuyun.common.exception.BizException BILL-1013（400 就诊号非住院形态）/
     *                 BILL-1022（404 该就诊号无押金账户）
     */
    @Operation(summary = "按就诊号查押金账户", operationId = "getDepositAccount")
    @GetMapping("/deposits")
    public DepositAccountVO getByVisit(@RequestParam("visitId") String visitId) {
        return depositService.getByVisit(visitId);
    }
}

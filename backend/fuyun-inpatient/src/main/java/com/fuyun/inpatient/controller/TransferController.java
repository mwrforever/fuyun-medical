package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.TransferCheckRequest;
import com.fuyun.inpatient.service.OrderTransferService;
import com.fuyun.inpatient.vo.TransferWorklistVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医嘱转抄端点（/api/v1/inpatient/transfer-worklist + /api/v1/inpatient/orders/transfer-check）
 * ——FU-M04-06 上（04-inpatient Spec §6）：转抄工作台待转抄列表（病区/班次聚合）与批量转抄
 * 核对（双人核对 AUDITED→TRANSFERRED + transferred 事件 + 临时医嘱单次计划）。controller
 * 禁业务逻辑与事务（A.1-8）：守卫链/状态机/计划生成全归服务层；写端点挂 WRITE 审计（GC22）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，OrderController 同款）。
 */
@Tag(name = "M04 医嘱转抄", description = "转抄工作台（病区/班次待转抄列表）/批量转抄核对（双人核对+临时单次计划）")
@RestController
@RequiredArgsConstructor
@Validated
public class TransferController {

    private final OrderTransferService orderTransferService;

    /**
     * 转抄工作台待转抄列表：病区在院就诊的 AUDITED 医嘱聚合（开立时间倒序），班次过滤按
     * 开立时点落班窗口（DAY/EVENING/NIGHT——V801 班次定义同源）。
     *
     * @param wardId 病区编码（必填过滤键），非空；来源：护士站病区上下文
     * @param shift  班次过滤（可空=全部班次），可空；来源：工作台班次切换
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 待转抄医嘱分页出参（含双人核对强制面提示 highRisk）
     * @throws com.fuyun.common.exception.BizException IP-1022（400 病区缺失/班次词表外）
     */
    @Operation(summary = "转抄工作台待转抄列表（病区/班次聚合 AUDITED 医嘱）", operationId = "listTransferWorklist")
    @GetMapping("/api/v1/inpatient/transfer-worklist")
    public PageResult<TransferWorklistVO> worklist(
            @RequestParam("wardId") @NotBlank String wardId,
            @RequestParam(value = "shift", required = false)
                    @Pattern(regexp = "DAY|EVENING|NIGHT", message = "班次词表外（DAY/EVENING/NIGHT）")
                    String shift,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return orderTransferService.worklist(wardId, shift, page, size);
    }

    /**
     * 批量转抄核对：整批单事务逐条 AUDITED→TRANSFERRED + 转抄台账 + inpatient.order.transferred
     * 事件 + 临时医嘱同步单次计划；高危/输血类缺第二核对人拒 IP-1016；已转抄幂等跳过。
     *
     * @param req 批量转抄入参（医嘱号集+转抄护士+核对结论+第二核对人），非空；来源：转抄工作台提交
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1016/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "批量转抄核对（双人核对→TRANSFERRED+transferred 事件+临时单次计划）", operationId = "transferCheckOrders")
    @PostMapping("/api/v1/inpatient/orders/transfer-check")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void transferCheck(@Valid @RequestBody TransferCheckRequest req) {
        orderTransferService.transferCheck(req);
    }
}

package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.service.IClinicOrderService;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 申请单端点（/api/v1/outpatient 前缀，M03 Spec :154：动作子路径 POST 形态）：作废与按就诊号查询。
 * 作废为诊疗关键动作 @AuditLog(WRITE) 留痕（Global Constraints 审计口径）。职责边界：仅参数校验+
 * 调用 service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "门诊申请单")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class OrderController {

    private final IClinicOrderService clinicOrderService;

    /**
     * 申请单作废：CREATED/PENDING_FEE→CANCELLED+PENDING 费用行逐行作废；CHARGED 拒绝引导退费链、
     * RX_REF 行引导 M06 作废链（均 OP-1015）。
     *
     * @param orderNo 申请单业务号，非空
     * @param reason  作废理由（审计留痕锚点），非空白
     * @return 作废后申请单出参（status=CANCELLED），非空
     */
    @Operation(summary = "申请单作废")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/orders/{no}/cancel")
    public ClinicOrderVO cancel(@PathVariable("no") String orderNo, @RequestParam String reason) {
        return clinicOrderService.cancel(orderNo, reason);
    }

    /**
     * 按就诊号查询申请单清单（医生站单据面）。
     *
     * @param visitId 就诊号，非空
     * @return 申请单出参清单（含明细行，id 降序）；无单据返回空列表
     */
    @Operation(summary = "按就诊号查询申请单")
    @GetMapping("/orders")
    public List<ClinicOrderVO> listByVisit(@RequestParam String visitId) {
        return clinicOrderService.listByVisit(visitId);
    }
}

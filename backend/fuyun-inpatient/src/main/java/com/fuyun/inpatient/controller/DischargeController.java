package com.fuyun.inpatient.controller;

import com.fuyun.inpatient.dto.DischargeConfirmRequest;
import com.fuyun.inpatient.dto.DischargeRequestCreate;
import com.fuyun.inpatient.service.DischargeService;
import com.fuyun.inpatient.vo.ClearanceVO;
import com.fuyun.inpatient.vo.DischargeRequestVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出院管理端点（/api/v1/inpatient/visits + /api/v1/inpatient/discharge-requests）——FU-M04-07
 * 四端点（04-inpatient Spec §7）：出院申请（在途清理+费用预审单事务编排）/取消出院（回在院，
 * 医嘱不复活）/清理与预审结果查询/离院确认（GC19 三重前置校验+床位消毒+带药放行+随访生成）。
 * controller 禁业务逻辑与事务（A.1-8）：守卫链/清理编排/状态机/事件发布全归服务层；写端点
 * 挂 WRITE 审计（@AuditLog 注解 + AuditLogAspect 上下文内拦截落 system.audit_log——GC22）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，AdmissionController 同款）。
 */
@Tag(name = "M04 出院管理", description = "出院申请/在途清理与费用预审/取消出院/离院确认")
@RestController
@RequiredArgsConstructor
@Validated
public class DischargeController {

    private final DischargeService dischargeService;

    /**
     * 出院申请（「预出院/明日出院」模式：在途清理编排三动作+费用预审单事务；预审 BLOCKED
     * 时出参附欠费额快照——走 M13 挂账审批，凭 billing.arrears.approved 转 READY）。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数）
     * @param req     申请入参（预出院时间/离院方式病案首页代码），非空；来源：医生站出院申请单
     * @return 申请出参（status=READY/BLOCKED 预审实态），非空
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1022/IP-1023/IP-1010
     *                 （服务接口注全清单）
     */
    @Operation(summary = "出院申请（在途清理+费用预审单事务编排）", operationId = "createDischargeRequest")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/discharge-request")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DischargeRequestVO createRequest(
            @PathVariable("visitId") String visitId, @Valid @RequestBody DischargeRequestCreate req) {
        return dischargeService.createRequest(visitId, req);
    }

    /**
     * 取消出院申请（仅 REQUESTED 态；visit 回 ADMITTED——长期医嘱不复活，恢复治疗须重新开立）。
     *
     * @param no 出院申请单号（路径参数）
     * @return 取消后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1018/IP-1017/IP-1022/IP-1023
     */
    @Operation(summary = "取消出院申请（回在院，医嘱不复活）", operationId = "cancelDischargeRequest")
    @PostMapping("/api/v1/inpatient/discharge-requests/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DischargeRequestVO cancel(@PathVariable("no") String no) {
        return dischargeService.cancel(no);
    }

    /**
     * 在途清理与预审结果查询（清理三动作计数快照+追踪清单+预审状态与欠费额+结算标记与
     * 挂账审批凭证——人工处置取数面）。
     *
     * @param no 出院申请单号（路径参数）
     * @return 清理与预审结果出参，非空
     * @throws com.fuyun.common.exception.BizException IP-1018/IP-1007
     */
    @Operation(summary = "在途清理与预审结果查询", operationId = "getDischargeClearance")
    @GetMapping("/api/v1/inpatient/discharge-requests/{no}/clearance")
    public ClearanceVO clearance(@PathVariable("no") String no) {
        return dischargeService.clearance(no);
    }

    /**
     * 离院确认（GC19 三重前置校验：长期医嘱终态+在途计划清零+预审 READY 且结算完成双条件；
     * 联动床位终末消毒、出院带药放行与随访生成）。
     *
     * @param no  出院申请单号（路径参数）
     * @param req 确认入参（随访三参数可选缺省 7 日/电话/「出院随访」），非空；来源：医生站出院确认单
     * @return 确认后出参（status=COMPLETED），非空
     * @throws com.fuyun.common.exception.BizException IP-1018/IP-1017/IP-1022/IP-1023/IP-1010
     */
    @Operation(summary = "离院确认（双条件放行+床位消毒+带药放行+随访生成）", operationId = "confirmDischarge")
    @PostMapping("/api/v1/inpatient/discharge-requests/{no}/confirm")
    @AuditLog(actionType = AuditActionType.WRITE)
    public DischargeRequestVO confirm(@PathVariable("no") String no, @Valid @RequestBody DischargeConfirmRequest req) {
        return dischargeService.confirm(no, req);
    }
}

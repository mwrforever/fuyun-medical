package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.OrderCancelRequest;
import com.fuyun.inpatient.dto.OrderCreateRequest;
import com.fuyun.inpatient.dto.OrderReorganizeRequest;
import com.fuyun.inpatient.dto.OrderStopRequest;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.service.MedicalOrderService;
import com.fuyun.inpatient.service.OrderAuditService;
import com.fuyun.inpatient.vo.MedicalOrderVO;
import com.fuyun.inpatient.vo.OrderDetailVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医嘱开立与控制端点（/api/v1/inpatient/visits/{visitId}/orders + /api/v1/inpatient/orders）
 * ——FU-M04-04/05（04-inpatient Spec §6）：开立（四层校验→CREATED→审核链收口→order.created
 * 子键路由）/分页查询/详情（含明细行）+ 控制六端点（停嘱/作废/撤回重审/重整/驳回重提/口头
 * 医嘱补录确认——状态迁移与事件发布全归服务层）。controller 禁业务逻辑与事务（A.1-8）：
 * 守卫链/状态机/事件发布全归服务层；写端点一律挂 WRITE 审计（GC22）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，AdmissionController 同款）。
 */
@Tag(name = "M04 医嘱开立", description = "医嘱开立（四层校验）/就诊医嘱分页/医嘱详情/医嘱控制（停嘱/作废/撤回/重整/重提/口头确认）")
@RestController
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final MedicalOrderService medicalOrderService;

    private final OrderAuditService orderAuditService;

    /**
     * 医嘱开立：在院校验→四层校验（执业授权/过敏/明细频次/嘱托限定）→CREATED 落库→
     * 发布 inpatient.order.created（routing key 携带类型子键）→审核链收口。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数），非空
     * @param req     开立入参（头+项列表），非空；来源：医生站开单
     * @return 开立后医嘱出参（status=审核链收口后实态）
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1012/IP-1013/IP-1011/
     *                 IP-1021/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "医嘱开立（四层校验→CREATED→审核链收口→order.created 子键路由）", operationId = "createMedicalOrder")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/orders")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MedicalOrderVO create(
            @PathVariable("visitId") @NotBlank String visitId, @Valid @RequestBody OrderCreateRequest req) {
        return medicalOrderService.create(visitId, req);
    }

    /**
     * 就诊医嘱分页查询（开立时间倒序；分类可叠加过滤）。
     *
     * @param visitId 住院就诊号（I 型 14 位，必填过滤键——住院医嘱视图以就诊为轴），非空；来源：查询参数
     * @param clazz   医嘱分类过滤（LONG/STAT；可空=全部分类），可空；来源：查询参数
     * @param page    页码（0 基），缺省 0
     * @param size    单页条数（1-200），缺省 20
     * @return 医嘱分页出参
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）
     */
    @Operation(summary = "就诊医嘱分页（开立时间倒序）", operationId = "listMedicalOrders")
    @GetMapping("/api/v1/inpatient/orders")
    public PageResult<MedicalOrderVO> list(
            @RequestParam("visitId") @NotBlank String visitId,
            @RequestParam(value = "class", required = false) OrderClass clazz,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return medicalOrderService.list(visitId, clazz, page, size);
    }

    /**
     * 医嘱详情（头 + 明细行全集，闭环追溯取数入口）。
     *
     * @param no 医嘱号（路径参数），非空
     * @return 医嘱详情出参（含项）
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1007（404
     *                 关联就诊不存在）
     */
    @Operation(summary = "医嘱详情（含明细行）", operationId = "getMedicalOrder")
    @GetMapping("/api/v1/inpatient/orders/{no}")
    public OrderDetailVO detail(@PathVariable("no") @NotBlank String no) {
        return medicalOrderService.detail(no);
    }

    /**
     * 医嘱停嘱：AUDITED/TRANSFERRED/EXECUTING→STOPPED + stopped 事件（M05 撤未执行执行单、
     * M13 截断持续性费用）。
     *
     * @param no  医嘱号（路径参数），非空
     * @param req 停嘱入参（临床停嘱依据），非空；来源：医生站停嘱录入
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1022（服务接口注全清单）
     */
    @Operation(summary = "医嘱停嘱（三态合法→STOPPED+stopped 事件）", operationId = "stopMedicalOrder")
    @PostMapping("/api/v1/inpatient/orders/{no}/stop")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void stop(@PathVariable("no") @NotBlank String no, @Valid @RequestBody OrderStopRequest req) {
        medicalOrderService.stop(no, req.reason());
    }

    /**
     * 医嘱作废：仅未产生执行（AUDITED/TRANSFERRED→CANCELLED，已执行拒 IP-1010）+ cancelled
     * 事件（执行单撤销归 M05 消费）。
     *
     * @param no  医嘱号（路径参数），非空
     * @param req 作废入参（作废原因），非空；来源：医生站作废录入
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1022（服务接口注全清单）
     */
    @Operation(summary = "医嘱作废（仅未产生执行→CANCELLED+cancelled 事件）", operationId = "cancelMedicalOrder")
    @PostMapping("/api/v1/inpatient/orders/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void cancel(@PathVariable("no") @NotBlank String no, @Valid @RequestBody OrderCancelRequest req) {
        orderAuditService.cancel(no, req.reason());
    }

    /**
     * 撤回审核：仅转抄前（AUDITED→CREATED 撤回重审；TRANSFERRED 起拒 IP-1010）+ revoked 事件
     * （权限控制 P3 注记——当前仅操作者上下文审计，角色限定待权限面引入）。
     *
     * @param no 医嘱号（路径参数），非空
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1022（服务接口注全清单）
     */
    @Operation(summary = "撤回审核（仅转抄前 AUDITED→CREATED+revoked 事件）", operationId = "revokeMedicalOrderAudit")
    @PostMapping("/api/v1/inpatient/orders/{no}/revoke-audit")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void revokeAudit(@PathVariable("no") @NotBlank String no) {
        orderAuditService.revokeAudit(no);
    }

    /**
     * 医嘱重整：只重排视图序+留痕（不改状态不改内容——列表顺序即新视图序，重整单取数锚）。
     *
     * @param req 重整入参（就诊号+新视图序医嘱号列表），非空；来源：医生站重整操作
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1009/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "医嘱重整（只重排视图序+留痕，不改状态）", operationId = "reorganizeMedicalOrders")
    @PostMapping("/api/v1/inpatient/orders/reorganize")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void reorganize(@Valid @RequestBody OrderReorganizeRequest req) {
        orderAuditService.reorganize(req);
    }

    /**
     * 医嘱驳回后修改重提：AUDIT_REJECTED→CREATED（载荷=修改后医嘱体，头值面与明细直接更新）
     * + 重发 order.created（M06 重开审方任务）+ 审核链重入。
     *
     * @param no  医嘱号（路径参数），非空
     * @param req 修改后医嘱体（与开立同构），非空；来源：医生站驳回重提编辑
     * @return 重提后医嘱出参（status=审核链收口后实态）
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1012/IP-1013/IP-1011/
     *                 IP-1021/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "医嘱驳回重提（AUDIT_REJECTED→CREATED+重发开立事件+审核链重入）", operationId = "resubmitMedicalOrder")
    @PostMapping("/api/v1/inpatient/orders/{no}/resubmit")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MedicalOrderVO resubmit(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody OrderCreateRequest req) {
        return medicalOrderService.resubmit(no, req);
    }

    /**
     * 抢救口头医嘱补录确认：oral_confirmed_at 落值（护士复诵执行后据实补记；限时催办 P3 注记
     * ——当前仅落确认时点）。
     *
     * @param no 医嘱号（路径参数），非空
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1010/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "抢救口头医嘱补录确认（oral_confirmed_at 落值）", operationId = "confirmOralMedicalOrder")
    @PostMapping("/api/v1/inpatient/orders/{no}/oral-confirm")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void oralConfirm(@PathVariable("no") @NotBlank String no) {
        orderAuditService.oralConfirm(no);
    }
}

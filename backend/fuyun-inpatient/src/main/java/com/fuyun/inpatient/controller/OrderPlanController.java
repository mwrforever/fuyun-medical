package com.fuyun.inpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.dto.ExecuteConfirmRequest;
import com.fuyun.inpatient.dto.StandbyTriggerRequest;
import com.fuyun.inpatient.service.OrderPlanService;
import com.fuyun.inpatient.service.OrderTransferService;
import com.fuyun.inpatient.vo.ExecuteConfirmVO;
import com.fuyun.inpatient.vo.OrderPlanVO;
import com.fuyun.inpatient.vo.OrderTraceVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行计划端点（/api/v1/inpatient/order-plans + /api/v1/inpatient/orders/{no}/trace）
 * ——FU-M04-06（04-inpatient Spec §6）：计划日视图分页查询、嘱托按需触发单次计划与
 * CF-6/W-33 执行回签实装（计划 PENDING→EXECUTED + 医嘱头三态推进 + executed 事件）及
 * 闭环追溯视图（brief 冻结：trace 端点落本控制器，方法级全路径承载）。controller 禁业务
 * 逻辑与事务（A.1-8）；写端点挂 WRITE 审计（GC22）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，OrderController 同款）。
 */
@Tag(name = "M04 执行计划", description = "执行计划日视图分页/嘱托按需触发单次计划/执行回签（CF-6）/医嘱闭环追溯")
@RestController
@RequiredArgsConstructor
@Validated
public class OrderPlanController {

    private final OrderTransferService orderTransferService;

    private final OrderPlanService orderPlanService;

    /**
     * 执行计划日视图：按计划日期（当日窗口）分页，病区可叠加过滤，计划时点升序。
     *
     * @param date   计划日期（必填过滤键——计划视图以日为轴，ISO yyyy-MM-dd），非空；来源：查询参数
     * @param wardId 病区过滤（可空=全部病区），可空；来源：查询参数
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 执行计划分页出参
     * @throws com.fuyun.common.exception.BizException IP-1022（400 日期缺失）；IP-1009/IP-1007
     *                 （404 关联行缺失——数据不一致）
     */
    @Operation(summary = "执行计划日视图分页（日期窗口+病区过滤）", operationId = "listOrderPlans")
    @GetMapping("/api/v1/inpatient/order-plans")
    public PageResult<OrderPlanVO> list(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "wardId", required = false) String wardId,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return orderTransferService.listPlans(date, wardId, page, size);
    }

    /**
     * 嘱托按需触发单次计划：长期备用嘱生成当次计划实例（多次触发多次台账，不重复计价由
     * M13 唯一键兜底）；医嘱头状态不迁移（回签面推进）。
     *
     * @param req 嘱托触发出入参（嘱托医嘱号），非空；来源：护士站嘱托医嘱卡触发
     * @return 当次生成的计划出参集（按明细行一至多条）
     * @throws com.fuyun.common.exception.BizException IP-1009/IP-1022/IP-1010/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "嘱托按需触发单次计划（多次触发多次台账）", operationId = "triggerStandbyOrderPlan")
    @PostMapping("/api/v1/inpatient/order-plans/standby-trigger")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<OrderPlanVO> standbyTrigger(@Valid @RequestBody StandbyTriggerRequest req) {
        return orderTransferService.standbyTrigger(req.orderNo());
    }

    /**
     * 执行回签（CF-6/W-33 契约实装，M05 主路径调用面）：计划 PENDING→EXECUTED（CAS）+
     * 医嘱头三态推进（长期首回签 TRANSFERRED→EXECUTING / 临时单次 TRANSFERRED→COMPLETED /
     * 全部计划终态 EXECUTING→COMPLETED）+ inpatient.order.executed 事件；重复回签已
     * EXECUTED 计划幂等返回当前状态（不迁移不发事件）。
     *
     * @param no  计划号（路径参数 {no}，PL+yyyyMMdd+5 位流水），非空；来源：M05 执行单回签/护士站
     * @param req 回签入参（executorId 必填/executedAt 可空缺省服务器时间/routeCheckResult 可空），非空
     * @return 回签出参（planNo/m04OrderNo/迁移后医嘱头状态/迁移后计划状态）
     * @throws com.fuyun.common.exception.BizException IP-1014/IP-1015/IP-1009/IP-1007/IP-1022/
     *                 IP-1010（服务接口注全清单）
     */
    @Operation(summary = "执行回签（CF-6：计划 PENDING→EXECUTED+医嘱头三态推进+executed 事件）", operationId = "executeConfirmOrderPlan")
    @PostMapping("/api/v1/inpatient/order-plans/{no}/execute-confirm")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ExecuteConfirmVO executeConfirm(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody ExecuteConfirmRequest req) {
        return orderPlanService.executeConfirm(no, req);
    }

    /**
     * 医嘱闭环追溯视图：开立→审核（含药师）→转抄→各计划执行→停止全环节人/时/果一屏聚合
     * （order_status_log + order_audit + order_transfer_log + order_execute_plan 四源时间线）。
     *
     * @param no 医嘱号（路径参数 {no}），非空
     * @return 追溯聚合出参（五环节时间线升序）
     * @throws com.fuyun.common.exception.BizException IP-1009（404 医嘱不存在）/IP-1007（404
     *                 关联就诊不存在——数据不一致）
     */
    @Operation(summary = "医嘱闭环追溯（开立→审核→转抄→计划执行→停止人/时/果一屏）", operationId = "traceMedicalOrder")
    @GetMapping("/api/v1/inpatient/orders/{no}/trace")
    public OrderTraceVO trace(@PathVariable("no") @NotBlank String no) {
        return orderPlanService.trace(no);
    }
}

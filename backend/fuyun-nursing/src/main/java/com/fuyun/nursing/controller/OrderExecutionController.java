package com.fuyun.nursing.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.dto.CancelExecutionRequest;
import com.fuyun.nursing.dto.CheckRequest;
import com.fuyun.nursing.dto.FinishRequest;
import com.fuyun.nursing.dto.OverrideCheckRequest;
import com.fuyun.nursing.dto.SignReceiveRequest;
import com.fuyun.nursing.dto.StartRequest;
import com.fuyun.nursing.service.IOrderExecutionOperateService;
import com.fuyun.nursing.vo.OrderExecutionTraceVO;
import com.fuyun.nursing.vo.OrderExecutionVO;
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
 * 医嘱执行单端点（/api/v1/nursing/executions + /api/v1/nursing/pda/override-check）
 * ——FU-M05-04b（05-nursing Spec）：五环节状态链操作族（补签收/三向扫码核对/开始/完成/
 * 撤销）+ 工作台分组清单 + 在途占用清单 + 闭环追溯 + 破码放行双授权。controller 禁业务
 * 逻辑与事务（A.1-8）；五环节操作与破码放行挂 WRITE 审计（GC27），查询面（工作台/占用/
 * 追溯）不加审计读注记（PR-4 W-47 统一收口患者查询 GET 审计面）。
 * 类级 @RequestMapping 不承载（方法级全路径自文档，OrderPlanController 同款）。
 */
@Tag(name = "M05 医嘱执行单", description = "执行工作台/五环节操作（签收·核对·开始·完成·撤销）/在途占用/闭环追溯/破码放行")
@RestController
@RequiredArgsConstructor
@Validated
public class OrderExecutionController {

    private final IOrderExecutionOperateService operateService;

    /**
     * 执行工作台分组清单：病区+日期窗口（缺省北京钟面当日）分页，班次（DAY/EVENING/NIGHT）
     * 按计划时间落班映射过滤，状态可选，计划时间升序。
     *
     * @param wardId 病区编码（必填过滤键），非空；来源：查询参数
     * @param date   计划日期（可空=北京钟面当日），可空；来源：查询参数
     * @param shift  班次过滤（DAY/EVENING/NIGHT，可空=全日），可空；来源：查询参数
     * @param status 状态过滤（ExecutionStatus code，可空），可空；来源：查询参数
     * @param page   页码（0 基），缺省 0
     * @param size   单页条数（1-200），缺省 20
     * @return 执行单分页出参（计划时间升序）
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 缺失/shift/status
     *                 code 非法）
     */
    @Operation(summary = "执行工作台分组清单（病区+日期+班次+状态，计划时间升序）", operationId = "listOrderExecutions")
    @GetMapping("/api/v1/nursing/executions")
    public PageResult<OrderExecutionVO> list(
            @RequestParam("wardId") @NotBlank String wardId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(value = "shift", required = false) String shift,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return operateService.listWorkbench(wardId, date, shift, status, page, size);
    }

    /**
     * 人工补签收（CREATED→SIGNED）：药品类主入口为摆药签收衔接（dispense 事件自动 SIGNED），
     * 本端点仅非药品类人工补签（receivedNote 审计留痕不落表）——应用日志 ≥6 个月留存的
     * 卷/轮转承载当前未落（现状 console-only 无落盘），随等保评审立项实装（工单 W-82）；
     * 结构化留痕承载留等保评审触发再立项。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 补签收入参（receivedNote 可空），非空
     * @return 签收后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404 执行单不存在）/ NS-1019
     *                 （400 操作者上下文缺失或非数字）/ NS-1021（409 非 CREATED 态）
     */
    @Operation(summary = "人工补签收（非药品类；药品类经摆药签收衔接自动签收）", operationId = "signReceiveOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/sign-receive")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO signReceive(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody SignReceiveRequest req) {
        return operateService.signReceive(no, req);
    }

    /**
     * 三向扫码核对（PASS→CHECKED）：按 codeType 单维核对——腕带=visitId 匹配、瓶签=
     * bag_label_code 匹配、执行单=execution_no 匹配；FAIL NS-1022 且流水落行（fail_type
     * 判定）不迁移状态。核对护士取操作者上下文（登录护士）。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 核对入参（code 扫码原文 + codeType 核对方式），非空
     * @return 核对通过后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1019（400 codeType
     *                 词表外/操作者上下文非法）/ NS-1022（409 核对不匹配）/ NS-1021（409
     *                 PASS 后状态前置不满足）
     */
    @Operation(summary = "三向扫码核对（腕带/瓶签/设备单维核对，PASS→CHECKED，FAIL 留痕拒绝）", operationId = "checkOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/check")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO check(@PathVariable("no") @NotBlank String no, @Valid @RequestBody CheckRequest req) {
        return operateService.check(no, req);
    }

    /**
     * 开始执行（CHECKED→EXECUTING）：计划时间窗外（±execute_time_window_minutes）未破码
     * NS-1027 拒绝；GENERIC 路径承载（INFUSION 建链/激活与 infusion.started 事件归输液闭环域
     * 在本 CAS 成功后段扩展）。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 开始入参（executorId 必填/deviceId 可空/overrideTimeWindow 可空），非空
     * @return 开始后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1027（409 计划时间
     *                 窗外未破码）/ NS-1021（409 非 CHECKED 态）
     */
    @Operation(summary = "开始执行（CHECKED→EXECUTING，时间窗外未破码拒绝）", operationId = "startOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/start")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO start(@PathVariable("no") @NotBlank String no, @Valid @RequestBody StartRequest req) {
        return operateService.start(no, req);
    }

    /**
     * 执行完成（EXECUTING→COMPLETED + 双路回签）：事务内发布 nursing.order-execution.completed
     * （辅路径）+ 事务提交后进程内回签 M04 计划（主路径，失败置 COMPENSATING 不抛出——床旁
     * 不阻塞）。INFUSION 型完成由输液闭环拔针端点承接。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 完成入参（executorId 必填/routeCheckResult 可空回签透传），非空
     * @return 完成后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 非
     *                 EXECUTING 态）
     */
    @Operation(summary = "执行完成（EXECUTING→COMPLETED+双路回签 M04）", operationId = "finishOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/finish")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO finish(@PathVariable("no") @NotBlank String no, @Valid @RequestBody FinishRequest req) {
        return operateService.finish(no, req);
    }

    /**
     * 执行单撤销：未执行三态（CREATED/SIGNED/CHECKED）→CANCELLED 原因留痕；EXECUTING 仅
     * INFUSION 型输注中断（护士长权限近似=操作者角色 ∈ override_roles），携 actualVolumeMl
     * 部分执行回签。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 撤销入参（reason 必填/actualVolumeMl 可空），非空
     * @return 撤销后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 GENERIC
     *                 型 EXECUTING 不可撤销/状态前置不满足）/ NS-1023（409 输注中断权限不符）
     */
    @Operation(summary = "执行单撤销（未执行三态常规撤销；EXECUTING 仅输注中断携量部分回签）", operationId = "cancelOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO cancel(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody CancelExecutionRequest req) {
        return operateService.cancel(no, req);
    }

    /**
     * 在途执行单占用清单（M13 退费前置校验消费）：非终态行集，患者/医嘱至少一键。
     *
     * @param patientId  患者主索引（与 m04OrderNo 至少一项），可空；来源：查询参数
     * @param m04OrderNo M04 医嘱号（与 patientId 至少一项），可空；来源：查询参数
     * @return 在途执行单出参清单（计划时间升序）
     * @throws com.fuyun.common.exception.BizException NS-1019（400 两过滤键均缺失）
     */
    @Operation(summary = "在途执行单占用清单（退费前置校验：非终态行集）", operationId = "listOrderExecutionOccupancy")
    @GetMapping("/api/v1/nursing/executions/occupancy")
    public List<OrderExecutionVO> occupancy(
            @RequestParam(value = "patientId", required = false) Long patientId,
            @RequestParam(value = "m04OrderNo", required = false) String m04OrderNo) {
        return operateService.occupancy(patientId, m04OrderNo);
    }

    /**
     * 单条执行单闭环追溯：五环节时点 + 扫码核对流水清单（含破码放行行）+ 关联告警号一屏聚合。
     *
     * @param no 执行单号（路径参数 {no}），非空
     * @return 追溯聚合出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404 执行单不存在）
     */
    @Operation(summary = "执行单闭环追溯（五环节时点+核对流水+关联告警一屏）", operationId = "traceOrderExecution")
    @GetMapping("/api/v1/nursing/executions/{no}/trace")
    public OrderExecutionTraceVO trace(@PathVariable("no") @NotBlank String no) {
        return operateService.trace(no);
    }

    /**
     * 破码放行双授权（PDA 面）：两人不同 + 操作者角色 ∈ override_roles（第二授权人角色面无
     * system 查询 api——当前操作者角色近似 + 审计留痕降级，RBAC 完整面归 PR-4 W-37）；
     * 通过→override_flag 置位 + OVERRIDE 流水落行，放行后续 start 跳过时间窗校验。
     *
     * @param req 放行入参（executionNo/双授权人/理由），非空
     * @return 放行后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1023（409 双授权
     *                 同一人或角色不在 override_roles）
     */
    @Operation(summary = "破码放行双授权（扫码核对失败后双人授权放行）", operationId = "overrideCheckOrderExecution")
    @PostMapping("/api/v1/nursing/pda/override-check")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO overrideCheck(@Valid @RequestBody OverrideCheckRequest req) {
        return operateService.overrideCheck(req);
    }
}

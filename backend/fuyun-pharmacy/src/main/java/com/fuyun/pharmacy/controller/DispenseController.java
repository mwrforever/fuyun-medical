package com.fuyun.pharmacy.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.DispensePlanDeliverRequest;
import com.fuyun.pharmacy.dto.DispensePlanGenerateRequest;
import com.fuyun.pharmacy.dto.DispensePlanReceiveRequest;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.dto.PickRequest;
import com.fuyun.pharmacy.dto.VerifyCredentialRequest;
import com.fuyun.pharmacy.service.IDispensePlanService;
import com.fuyun.pharmacy.service.IDispenseService;
import com.fuyun.pharmacy.vo.DispensePlanLabelVO;
import com.fuyun.pharmacy.vo.DispensePlanVO;
import com.fuyun.pharmacy.vo.DispenseVO;
import com.fuyun.pharmacy.vo.OccupancyVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调剂端点（/api/v1/pharmacy/dispenses 与退药受理 /api/v1/pharmacy/dispense-returns，
 * Spec :171 顶层路径）：pick/verify/issue 调剂三段、退药受理两时点与工作台按处方号回显；
 * 三段与退药受理为法定留痕操作全量审计（WRITE）。调用方：M06 药师工作站 / IT 直调模拟 / M05 病区退药发起。
 * P2 PR-3 Task 8 扩住院摆药面（/api/v1/pharmacy/dispense-plans 族）：计划生成/摆药流五步/
 * 分页查询/PIVAS 贴签数据面——摆药五步为法定留痕操作全量审计（WRITE），GET 两端点按
 * Task 5/6 minor 口径不挂审计注记（查询面 W-47 统一收口，最终审查裁定）。
 * 类级 @RequestMapping 不承载（dispense-returns/dispense-plans 为 dispenses 的兄弟顶层路径），
 * 各方法携全路径。
 */
@Tag(name = "调剂")
@RestController
@RequiredArgsConstructor
public class DispenseController {

    private final IDispenseService dispenseService;

    private final IDispensePlanService dispensePlanService;

    /**
     * 配药（CREATED→PICKING）：FEFO 选批锁定批次 + 追溯码逐盒采集（「无码不结」）。
     *
     * @param no  调剂单号（路径参数）
     * @param req 逐行采集入参，非空
     */
    @Operation(summary = "配药")
    @PostMapping("/api/v1/pharmacy/dispenses/{no}/pick")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void pick(@PathVariable("no") String no, @Valid @RequestBody PickRequest req) {
        dispenseService.pick(no, req.items());
    }

    /**
     * 扫码核对（PICKING→PICKED）：核对药师=当前登录者，双签分权后端硬守卫（PH-1011）；
     * body 可选携取药凭证（settlementNo）——非空时核验其与处方归属一致性（PH-1018），
     * 缺省/空凭证跳过核验（追溯码防回流主道不变）。
     *
     * @param no  调剂单号（路径参数）
     * @param req 取药凭证核验入参，可缺省（body 缺省即 null 安全跳过）
     */
    @Operation(summary = "扫码核对")
    @PostMapping("/api/v1/pharmacy/dispenses/{no}/verify")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void verify(
            @PathVariable("no") String no, @Valid @RequestBody(required = false) VerifyCredentialRequest req) {
        dispenseService.verify(no, req == null ? null : req.credential());
    }

    /**
     * 发药签名（PICKED→ISSUED）：批次扣减+出库流水同事务，处方转 DISPENSED，
     * 发布 pharmacy.dispense.completed（批次摘要携追溯码）。
     *
     * @param no 调剂单号（路径参数）
     */
    @Operation(summary = "发药签名")
    @PostMapping("/api/v1/pharmacy/dispenses/{no}/issue")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void issue(@PathVariable("no") String no) {
        dispenseService.issue(no);
    }

    /**
     * 退药受理（R2-13 两时点）：ISSUED_RETURN 实物退（追溯码防回流核验+批次回补+流水冲正+
     * 单据 PART/FULL_RETURNED，发 pharmacy.dispense.returned）/ DISPENSING_CANCEL 发药中
     * 明细退场（释放锁定批次，处方保持 DISPENSING）。
     *
     * @param req 退药受理入参（单号/受理模式/逐行退药面），非空
     */
    @Operation(summary = "退药受理")
    @PostMapping("/api/v1/pharmacy/dispense-returns")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void acceptReturn(@Valid @RequestBody DispenseReturnRequest req) {
        dispenseService.acceptReturn(req);
    }

    /**
     * 执行占用查询（供 M13 位；billing 不切注记维持，P3 接入）。
     *
     * @param patientId 患者 id，必填
     * @param visitId   就诊号，可空
     * @param itemCode  收费项目 code，可空
     * @return 占用行集
     */
    @Operation(summary = "执行占用查询")
    @GetMapping("/api/v1/pharmacy/medication-occupancy")
    public List<OccupancyVO> occupancy(
            @RequestParam long patientId,
            @RequestParam(required = false) String visitId,
            @RequestParam(required = false) String itemCode) {
        return dispenseService.occupancy(patientId, visitId, itemCode);
    }

    /**
     * 按处方号查发药单（工作台单处方维度检回；无单为空数组，禁单行 null 出网）。
     *
     * @param rxNo 处方号，必填
     * @return 发药单出参清单（一处方一张活动单，取首行即单据面）
     */
    @Operation(summary = "按处方号查发药单")
    @GetMapping("/api/v1/pharmacy/dispenses")
    public List<DispenseVO> getByRxNo(@RequestParam("rxNo") String rxNo) {
        DispenseVO vo = dispenseService.getByRxNo(rxNo);
        return vo == null ? List.of() : List.of(vo);
    }

    /**
     * 生成住院摆药计划（APPROVED 前置+长期频次分解+plan_type 判定+uk 幂等）。
     *
     * @param req 生成入参（医嘱号+目标病区），非空
     * @return 该医嘱全部未删计划（幂等稳定输出，planTime 升序）
     */
    @Operation(summary = "生成住院摆药计划")
    @PostMapping("/api/v1/pharmacy/dispense-plans/generate")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<DispensePlanVO> generate(@Valid @RequestBody DispensePlanGenerateRequest req) {
        return dispensePlanService.generate(req);
    }

    /**
     * 摆药开始（CREATED→PICKING；单剂量=人工摆药+库存预校验、PIVAS=排药+排批号）。
     *
     * @param no 摆药计划号（路径参数）
     */
    @Operation(summary = "摆药开始")
    @PostMapping("/api/v1/pharmacy/dispense-plans/{no}/pick")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void pickPlan(@PathVariable("no") String no) {
        dispensePlanService.pick(no);
    }

    /**
     * 药师核对（PICKING→PICKED；PIVAS 链=贴签核对 label_printed 置位）。
     *
     * @param no 摆药计划号（路径参数）
     */
    @Operation(summary = "摆药核对")
    @PostMapping("/api/v1/pharmacy/dispense-plans/{no}/verify")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void verifyPlan(@PathVariable("no") String no) {
        dispensePlanService.verify(no);
    }

    /**
     * 出库交接（PICKED→CHECKED；落调剂行+库存扣减+批次回填）。
     *
     * @param no 摆药计划号（路径参数）
     */
    @Operation(summary = "摆药出库交接")
    @PostMapping("/api/v1/pharmacy/dispense-plans/{no}/issue")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void issuePlan(@PathVariable("no") String no) {
        dispensePlanService.issue(no);
    }

    /**
     * 配送交接（CHECKED 态内 issued_at 时间线半步——不迁移状态，签收归 receive）。
     *
     * @param no  摆药计划号（路径参数）
     * @param req 配送交接入参（carrier 可空），可缺省
     */
    @Operation(summary = "摆药配送交接")
    @PostMapping("/api/v1/pharmacy/dispense-plans/{no}/deliver")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void deliverPlan(
            @PathVariable("no") String no, @Valid @RequestBody(required = false) DispensePlanDeliverRequest req) {
        dispensePlanService.deliver(no, req == null ? null : req.carrier());
    }

    /**
     * 病区签收（CHECKED→DELIVERED CAS+事务内发布 pharmacy.dispense.completed 住院四字段载荷）。
     * W-72：签收人=登录令牌身份（服务端强制落值）；请求体保留解析（receivedBy 字段兼容期
     * 可传可不传，服务端不消费）。
     *
     * @param no  摆药计划号（路径参数）
     * @param req 签收入参（receivedBy 兼容保留——忽略），非空
     */
    @Operation(summary = "摆药病区签收（签收人=登录令牌身份）")
    @PostMapping("/api/v1/pharmacy/dispense-plans/{no}/receive")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void receivePlan(@PathVariable("no") String no, @Valid @RequestBody DispensePlanReceiveRequest req) {
        dispensePlanService.receive(no);
    }

    /**
     * 住院摆药计划分页查询（病区工作台高频过滤面；GET 不挂审计——查询面统一收口口径）。
     *
     * @param m04OrderNo 住院医嘱号过滤，可空
     * @param wardId     病区编码过滤，可空
     * @param status     计划状态过滤，可空
     * @param page       页码（0 基），默认 0
     * @param size       页大小，默认 20
     * @return 分页结果
     */
    @Operation(summary = "住院摆药计划分页查询")
    @GetMapping("/api/v1/pharmacy/dispense-plans")
    public PageResult<DispensePlanVO> pagePlans(
            @RequestParam(required = false) String m04OrderNo,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dispensePlanService.page(m04OrderNo, wardId, status, page, size);
    }

    /**
     * PIVAS 贴签数据面（患者脱敏名/病区/排批/调配核对双人/药品明细——打印归 M01 降级注记）。
     *
     * @param no 摆药计划号（路径参数）
     * @return 贴签数据面
     */
    @Operation(summary = "PIVAS 贴签数据面")
    @GetMapping("/api/v1/pharmacy/dispense-plans/{no}/label")
    public DispensePlanLabelVO label(@PathVariable("no") String no) {
        return dispensePlanService.label(no);
    }
}

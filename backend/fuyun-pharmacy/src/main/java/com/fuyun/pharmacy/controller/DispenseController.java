package com.fuyun.pharmacy.controller;

import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.dto.PickRequest;
import com.fuyun.pharmacy.service.IDispenseService;
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
 * 类级 @RequestMapping 不承载（dispense-returns 为 dispenses 的兄弟顶层路径），各方法携全路径。
 */
@Tag(name = "调剂")
@RestController
@RequiredArgsConstructor
public class DispenseController {

    private final IDispenseService dispenseService;

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
     * 扫码核对（PICKING→PICKED）：核对药师=当前登录者，双签分权后端硬守卫（PH-1011）。
     *
     * @param no 调剂单号（路径参数）
     */
    @Operation(summary = "扫码核对")
    @PostMapping("/api/v1/pharmacy/dispenses/{no}/verify")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void verify(@PathVariable("no") String no) {
        dispenseService.verify(no);
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
}

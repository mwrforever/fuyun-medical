package com.fuyun.pharmacy.controller;

import com.fuyun.pharmacy.dto.PickRequest;
import com.fuyun.pharmacy.service.IDispenseService;
import com.fuyun.pharmacy.vo.DispenseVO;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调剂端点（/api/v1/pharmacy/dispenses，Spec :171）：pick/verify/issue 调剂三段与工作台
 * 按处方号回显；三段为法定留痕操作全量审计（WRITE）。调用方：M06 药师工作站 / IT 直调模拟；
 * 退药受理端点随 Task 7 追加本类。
 */
@Tag(name = "调剂")
@RestController
@RequestMapping("/api/v1/pharmacy/dispenses")
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
    @PostMapping("/{no}/pick")
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
    @PostMapping("/{no}/verify")
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
    @PostMapping("/{no}/issue")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void issue(@PathVariable("no") String no) {
        dispenseService.issue(no);
    }

    /**
     * 按处方号查发药单（工作台单处方维度检回；无单为空数组，禁单行 null 出网）。
     *
     * @param rxNo 处方号，必填
     * @return 发药单出参清单（一处方一张活动单，取首行即单据面）
     */
    @Operation(summary = "按处方号查发药单")
    @GetMapping
    public List<DispenseVO> getByRxNo(@RequestParam("rxNo") String rxNo) {
        DispenseVO vo = dispenseService.getByRxNo(rxNo);
        return vo == null ? List.of() : List.of(vo);
    }
}

package com.fuyun.pharmacy.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.PrescriptionCancelRequest;
import com.fuyun.pharmacy.dto.PrescriptionCreateRequest;
import com.fuyun.pharmacy.service.IPrescriptionService;
import com.fuyun.pharmacy.vo.PrescriptionVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处方端点（/api/v1/pharmacy/prescriptions，Spec :169）：开方（同步返回处方号+预检分级）、
 * 作废、分页查询。调用方：M03 医生站（PR-5）/workstation/IT 直调模拟。
 */
@Tag(name = "处方")
@RestController
@RequestMapping("/api/v1/pharmacy/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final IPrescriptionService prescriptionService;

    /**
     * 开方。
     *
     * @param req 开方入参，非空
     * @return 处方出参（rxNo+reviewLevel=PASS）
     */
    @Operation(summary = "开方")
    @PostMapping
    @AuditLog(actionType = AuditActionType.WRITE)
    public PrescriptionVO create(@Valid @RequestBody PrescriptionCreateRequest req) {
        return prescriptionService.create(req);
    }

    /**
     * 作废（未缴费联动费用作废；已缴费拒 PH-1014 引导退药/退费）。
     *
     * @param no  处方号（路径参数）
     * @param req 作废入参（原因必填）
     */
    @Operation(summary = "处方作废")
    @PostMapping("/{no}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void cancel(@PathVariable("no") String no, @Valid @RequestBody PrescriptionCancelRequest req) {
        prescriptionService.cancel(no, req.reason());
    }

    /**
     * 分页查询（visitId/patientId/rxNo/status 任意组合；status=PENDING_DISPENSE 即药房队列）。
     *
     * @param visitId   就诊号，可空
     * @param patientId 患者 id，可空
     * @param rxNo      处方号，可空
     * @param status    状态过滤，可空
     * @param page      0 基页码
     * @param size      页大小
     * @return 分页出参
     */
    @Operation(summary = "处方分页查询")
    @GetMapping
    public PageResult<PrescriptionVO> list(
            @RequestParam(required = false) String visitId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String rxNo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return prescriptionService.list(visitId, patientId, rxNo, status, page, size);
    }
}

package com.fuyun.inpatient.controller;

import com.fuyun.inpatient.dto.ChangeBedRequest;
import com.fuyun.inpatient.dto.TransferRequest;
import com.fuyun.inpatient.service.TransferService;
import com.fuyun.inpatient.vo.TransferResultVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理单元变更端点（/api/v1/inpatient/visits/{visitId}/transfer 与 /change-bed）——
 * FU-M04-02：转科四阶段编排与同病区转床轻量路径（04-inpatient Spec §3.5）。controller
 * 禁业务逻辑与事务（A.1-8）：编排事务、医嘱停嘱联动与事件发布全归服务层；写端点挂 WRITE
 * 审计。类级 @RequestMapping 不承载（方法级全路径自文档）。
 */
@Tag(name = "M04 转科转床", description = "转科四阶段编排/同病区转床轻量路径")
@RestController
@RequiredArgsConstructor
@Validated
public class VisitTransferController {

    private final TransferService transferService;

    /**
     * 转科四阶段编排（单事务：停嘱→在途三分→床位流转→transferred 事件）。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数）
     * @param req     转科入参（目标科室/病区/床位），非空；来源：医生站转科单
     * @return 编排出参（前后定位面与完成时点）
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1004/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "转科四阶段编排（停嘱+三分+床位流转+事件）", operationId = "transferVisit")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/transfer")
    @AuditLog(actionType = AuditActionType.WRITE)
    public TransferResultVO transfer(
            @PathVariable("visitId") @NotBlank(message = "visitId 不能为空") String visitId,
            @Valid @RequestBody TransferRequest req) {
        return transferService.transfer(visitId, req);
    }

    /**
     * 同病区转床轻量路径（单事务：床位流转+事件，无停嘱步骤）。
     *
     * @param visitId 住院就诊号（I 型 14 位，路径参数）
     * @param req     转床入参（目标床位），非空；来源：护士站床位调整
     * @return 编排出参（前后定位面与完成时点；前后病区相同）
     * @throws com.fuyun.common.exception.BizException IP-1007/IP-1008/IP-1004/IP-1022/IP-1023（服务接口注全清单）
     */
    @Operation(summary = "同病区转床轻量路径（无停嘱）", operationId = "changeBed")
    @PostMapping("/api/v1/inpatient/visits/{visitId}/change-bed")
    @AuditLog(actionType = AuditActionType.WRITE)
    public TransferResultVO changeBed(
            @PathVariable("visitId") @NotBlank(message = "visitId 不能为空") String visitId,
            @Valid @RequestBody ChangeBedRequest req) {
        return transferService.changeBed(visitId, req);
    }
}

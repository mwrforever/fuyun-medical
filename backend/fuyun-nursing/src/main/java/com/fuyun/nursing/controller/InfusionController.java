package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.NeedleOutRequest;
import com.fuyun.nursing.service.IInfusionService;
import com.fuyun.nursing.service.IOrderExecutionOperateService;
import com.fuyun.nursing.service.IWardAccessService;
import com.fuyun.nursing.vo.ActiveInfusionVO;
import com.fuyun.nursing.vo.OrderExecutionVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 输液闭环端点（/api/v1/nursing，Task 6 / FU-M05-06 方案 3.4）：拔针（INFUSION 型 finish
 * 承接——腕带核对+自动入量+监测收口+双路回签）与病区在途输注清单。controller 禁业务逻辑
 * 与事务（A.1-8）；拔针挂 WRITE 审计（GC27 五环节操作族），在途清单为查询面不加审计读
 * 注记（Task 5 minor-3 口径：GET 挂 WRITE 语义不当，PR-4 W-47 统一收口）。类级
 * @RequestMapping 不承载（方法级全路径自文档，OrderExecutionController 同款）。
 */
@Tag(name = "M05 输液闭环", description = "拔针（腕带核对+自动入量）/病区在途输注清单")
@RestController
@RequiredArgsConstructor
@Validated
public class InfusionController {

    private final IOrderExecutionOperateService operateService;

    private final IInfusionService infusionService;

    private final IWardAccessService wardAccessService;

    /**
     * 输液拔针（EXECUTING 输液执行单完成形态）：腕带三向核对（FAIL NS-1022 流水留痕）+
     * 实际输注量确认（0~5000 越界 400）+ 监测挂接收口（无在途监测 NS-1024）+ 自动入量行
     * （INFUSION_AUTO/IV_FLUID）+ infusion.completed 事件 + 回签同 finish 双路。
     *
     * @param no  执行单号（路径参数 {no}），非空
     * @param req 拔针入参（executorId/actualVolumeMl/wristbandCode 全必填），非空
     * @return 拔针后执行单出参
     * @throws com.fuyun.common.exception.BizException NS-1020（404）/ NS-1021（409 GENERIC 型
     *                 或非 EXECUTING 态）/ NS-1022（409 腕带核对不符）/ NS-1019（400 输注量越界）/
     *                 NS-1024（409 无在途输注监测挂接）/ NS-1004（409 患者不在区）
     */
    @Operation(summary = "输液拔针（腕带核对+自动入量+监测收口+双路回签）", operationId = "needleOutOrderExecution")
    @PostMapping("/api/v1/nursing/executions/{no}/needle-out")
    @AuditLog(actionType = AuditActionType.WRITE)
    public OrderExecutionVO needleOut(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody NeedleOutRequest req) {
        return operateService.needleOut(no, req);
    }

    /**
     * 病区在途输注清单：EXECUTING 输液执行单 × MONITORING 监测挂接聚合（开始输注时点升序），
     * 供大屏/工作台输液看板与告警挂单定位。
     * W-40：请求病区须 ∈ 操作者当班绑定集（fail-closed，NS-1028）。
     *
     * @param wardId 病区编码（必填过滤键），非空；来源：查询参数
     * @return 在途输注出参清单（无在途返回空清单）
     * @throws com.fuyun.common.exception.BizException NS-1019（400 wardId 缺失）/ NS-1028
     *                 （403 病区不在当班绑定集或无有效绑定）
     */
    @Operation(summary = "病区在途输注清单（监测挂接聚合，开始时点升序）", operationId = "listActiveInfusions")
    @GetMapping("/api/v1/nursing/infusions/active")
    public List<ActiveInfusionVO> active(@RequestParam("wardId") @NotBlank String wardId) {
        // W-40：请求病区须 ∈ 操作者当班绑定集（fail-closed，NS-1028）
        wardAccessService.assertWardAllowed(wardId);
        return infusionService.listActive(wardId);
    }
}

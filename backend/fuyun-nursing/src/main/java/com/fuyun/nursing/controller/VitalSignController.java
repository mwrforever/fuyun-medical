package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.VitalSignRecordRequest;
import com.fuyun.nursing.dto.VitalSignRejectRequest;
import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.vo.VitalSignVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生命体征端点（/api/v1/nursing/vital-signs，Spec :159 端点面）。录入/转正/驳回为写面
 * （审计留痕），复核为动作子路径 POST（GC13）；iot-sync 手动触发同步归 P2 不设端点
 * （GC17-① IoT 降级）。体温单条目与观察行无公开端点——由体征域服务面写入，禁端点直写
 * 绕过归集链（TemperatureChartController 同款口径）。类级 @RequestMapping 不承载
 * （WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "生命体征")
@RestController
@RequiredArgsConstructor
public class VitalSignController {

    private final IVitalSignService vitalSignService;

    /**
     * 体征录入（手工/PDA 点测，录入即 CONFIRMED；生理极限越界拒收 NS-1005）。
     *
     * @param req 录入入参，非空
     * @return 体征记录出参
     */
    @Operation(summary = "体征录入（手工/PDA 点测）")
    @PostMapping("/api/v1/nursing/vital-signs")
    @AuditLog(actionType = AuditActionType.WRITE)
    public VitalSignVO record(@Valid @RequestBody VitalSignRecordRequest req) {
        return vitalSignService.record(req);
    }

    /**
     * 按患者主索引列体征记录（from/to 均可空，窗口含头不含尾；测量时点升序）。
     *
     * @param patientId 患者主索引，必填
     * @param from      窗口起点（含），可空
     * @param to        窗口终点（不含），可空
     * @return 体征出参清单（测量时点升序）
     */
    @Operation(summary = "患者体征查询")
    @GetMapping("/api/v1/nursing/vital-signs")
    public List<VitalSignVO> listByPatient(
            @RequestParam("patientId") long patientId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return vitalSignService.listByPatient(patientId, from, to);
    }

    /**
     * 病区待复核体征清单（复核工作台数据源，仅 PENDING_REVIEW 行）。
     *
     * @param wardId 病区编码，必填
     * @return 待复核出参清单（测量时点升序）
     */
    @Operation(summary = "病区待复核体征清单")
    @GetMapping("/api/v1/nursing/vital-signs/pending-review")
    public List<VitalSignVO> pendingReview(@RequestParam("wardId") String wardId) {
        return vitalSignService.pendingReview(wardId);
    }

    /**
     * 体征复核转正（PENDING_REVIEW→CONFIRMED，补写体温单条目并发布转正入卡事件）。
     *
     * @param id 体征记录 id（路径参数）
     * @return 转正后记录出参
     */
    @Operation(summary = "体征复核转正")
    @PostMapping("/api/v1/nursing/vital-signs/{id}/confirm")
    @AuditLog(actionType = AuditActionType.WRITE)
    public VitalSignVO confirm(@PathVariable("id") long id) {
        return vitalSignService.confirm(id);
    }

    /**
     * 体征复核驳回（PENDING_REVIEW→REJECTED，原因留痕，不入权威栏）。
     *
     * @param id  体征记录 id（路径参数）
     * @param req 驳回入参，非空
     * @return 驳回后记录出参
     */
    @Operation(summary = "体征复核驳回")
    @PostMapping("/api/v1/nursing/vital-signs/{id}/reject")
    @AuditLog(actionType = AuditActionType.WRITE)
    public VitalSignVO reject(@PathVariable("id") long id, @Valid @RequestBody VitalSignRejectRequest req) {
        return vitalSignService.reject(id, req);
    }
}

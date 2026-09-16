package com.fuyun.patient.controller;

import com.fuyun.patient.dto.HealthItemCorrectRequest;
import com.fuyun.patient.dto.HealthItemCreateRequest;
import com.fuyun.patient.service.IHealthSummaryService;
import com.fuyun.patient.vo.HealthItemVO;
import com.fuyun.patient.vo.HealthSummaryVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康档案端点（M02 Spec §7 FU-M02-05）：GET /patients/{patientId}/health-summary +
 * POST /patients/{patientId}/health-items + POST /health-items/{id}/correct。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：纠错留痕与事务边界在 service impl 方法级；
 * 写端点挂 WRITE 审计（临床档案修正留痕口径），摘要查询只读不审计。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class HealthController {

    private final IHealthSummaryService healthSummaryService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param healthSummaryService 健康档案服务（摘要/明细/纠错留痕），非空
     */
    public HealthController(IHealthSummaryService healthSummaryService) {
        this.healthSummaryService = healthSummaryService;
    }

    /**
     * 健康档案摘要（GET /patients/{patientId}/health-summary，只读不审计）。
     *
     * @param patientId 患者主索引（路径变量）
     * @return 摘要出参（无聚合行为空摘要）；200
     * @throws com.fuyun.common.exception.BizException PAT-1001（404 档案不存在）
     */
    @GetMapping("/patients/{patientId}/health-summary")
    public HealthSummaryVO summary(@PathVariable long patientId) {
        return healthSummaryService.getSummary(patientId);
    }

    /**
     * 新增健康档案项（POST /patients/{patientId}/health-items；WRITE 审计；刷新锚点 + 变更事件）。
     *
     * @param patientId 患者主索引（路径变量）
     * @param request   新增请求（@Valid）；来源：临床医生站/手工补录
     * @return 明细出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1001（404 档案不存在）
     */
    @PostMapping("/patients/{patientId}/health-items")
    @AuditLog(actionType = AuditActionType.WRITE)
    public HealthItemVO addItem(@PathVariable long patientId, @Valid @RequestBody HealthItemCreateRequest request) {
        return healthSummaryService.addItem(patientId, request);
    }

    /**
     * 纠错（POST /health-items/{id}/correct；WRITE 审计；旧行置 CORRECTED 保留，新行回链全程留痕）。
     *
     * @param id      被纠错明细 id（路径变量）
     * @param request 纠错请求（@Valid，纠错说明非空）
     * @return 新明细出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1017（404 明细不存在）
     */
    @PostMapping("/health-items/{id}/correct")
    @AuditLog(actionType = AuditActionType.WRITE)
    public HealthItemVO correct(@PathVariable long id, @Valid @RequestBody HealthItemCorrectRequest request) {
        return healthSummaryService.correct(id, request);
    }
}

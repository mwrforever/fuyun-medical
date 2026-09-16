package com.fuyun.patient.controller;

import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.service.PatientRegistrationService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.enums.AuditActionType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者建档与查询端点（M02 Spec §7 REST 前缀 /api/v1/patient/；本任务交付建档两端点，
 * 查询/更新/检索/冻结随 Task 6 扩充）。
 *
 * <p>审计落点（M02 Spec §8 依赖上游：建档全量留痕）：建档 WRITE 审计由 M01 切面承载
 * （@AuditLog 注解 + AuditLogAspect 上下文内拦截），controller 禁业务逻辑与事务（A.1-8）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PatientController {

    private final PatientRegistrationService registrationService;

    private final PatientMatchingService matchingService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param registrationService 建档主用例服务，非空
     * @param matchingService     匹配引擎（预检直通），非空
     */
    public PatientController(PatientRegistrationService registrationService, PatientMatchingService matchingService) {
        this.registrationService = registrationService;
        this.matchingService = matchingService;
    }

    /**
     * 患者建档（POST /patients，FU-M02-01）。
     *
     * @param request 建档请求（@Valid 声明式校验）；来源：workstation 建档表单
     * @return 建档结果（outcome + patientId 载体）；201
     */
    @PostMapping("/patients")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public PatientMatchCheckVO create(@Valid @RequestBody PatientCreateRequest request) {
        return registrationService.register(request);
    }

    /**
     * 建档前匹配预检（POST /patients/match-check；只读不落库）。
     *
     * @param request 预检请求；来源：建档表单「预检」交互
     * @return 匹配结论；200
     */
    @PostMapping("/patients/match-check")
    public PatientMatchCheckVO matchCheck(@Valid @RequestBody PatientMatchCheckRequest request) {
        return matchingService.preCheck(request);
    }
}

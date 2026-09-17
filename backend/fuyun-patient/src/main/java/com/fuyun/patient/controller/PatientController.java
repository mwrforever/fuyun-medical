package com.fuyun.patient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.FreezeRequest;
import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.dto.PatientSearchQuery;
import com.fuyun.patient.dto.PatientUpdateRequest;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.service.PatientRegistrationService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import com.fuyun.patient.vo.PatientVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者建档与查询端点（M02 Spec §7 REST 前缀 /api/v1/patient/）：建档两端点（Task 5）+
 * 详情/更新/检索/冻结/解冻五端点（Task 6）。
 *
 * <p>审计落点（M02 Spec §8 依赖上游：建档/更新/冻结全量留痕）：WRITE 审计由 M01 切面承载
 * （@AuditLog 注解 + AuditLogAspect 上下文内拦截），controller 禁业务逻辑与事务（A.1-8）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PatientController {

    private final PatientRegistrationService registrationService;

    private final PatientMatchingService matchingService;

    private final IPatientService patientService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param registrationService 建档主用例服务，非空
     * @param matchingService     匹配引擎（预检直通），非空
     * @param patientService      患者主索引服务（详情/更新/检索/冻结），非空
     */
    public PatientController(
            PatientRegistrationService registrationService,
            PatientMatchingService matchingService,
            IPatientService patientService) {
        this.registrationService = registrationService;
        this.matchingService = matchingService;
        this.patientService = patientService;
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

    /**
     * 档案详情（GET /patients/{patientId}，脱敏输出）。
     *
     * @param patientId 患者主索引（路径变量）
     * @return 脱敏出参；200
     */
    @GetMapping("/patients/{patientId}")
    public PatientVO detail(@PathVariable long patientId) {
        return patientService.getDetail(patientId);
    }

    /**
     * 主数据更新（PUT /patients/{patientId}，WRITE 审计落点）。
     *
     * @param patientId 患者主索引
     * @param request   部分更新请求（@Valid）
     * @return 变更字段清单；200
     */
    @PutMapping("/patients/{patientId}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public List<String> update(@PathVariable long patientId, @Valid @RequestBody PatientUpdateRequest request) {
        return patientService.update(patientId, request);
    }

    /**
     * 患者检索（GET /patients/search，脱敏分页；空关键词返回空数据页防全表拉取）。
     *
     * @param keyword 检索词（可空）
     * @param page    页码（0 基，缺省 0）
     * @param size    单页条数（1-200，越界收敛）
     * @return 脱敏分页；200
     */
    @GetMapping("/patients/search")
    public PageResult<PatientVO> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return patientService.search(new PatientSearchQuery(keyword, page, Math.min(Math.max(size, 1), 200)));
    }

    /**
     * 冻结档案（POST /patients/{patientId}/freeze，拍板 2 新增端点；WRITE 审计）。
     *
     * @param patientId 患者主索引
     * @param request   冻结原因（@Valid 非空）
     * @return 204
     */
    @PostMapping("/patients/{patientId}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void freeze(@PathVariable long patientId, @Valid @RequestBody FreezeRequest request) {
        patientService.freeze(patientId, request.reason());
    }

    /**
     * 解冻档案（POST /patients/{patientId}/unfreeze；WRITE 审计）。
     *
     * @param patientId 患者主索引
     * @return 204
     */
    @PostMapping("/patients/{patientId}/unfreeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void unfreeze(@PathVariable long patientId) {
        patientService.unfreeze(patientId);
    }
}

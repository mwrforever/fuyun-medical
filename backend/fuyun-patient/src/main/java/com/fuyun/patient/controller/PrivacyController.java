package com.fuyun.patient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.PrivacyAccessLogQuery;
import com.fuyun.patient.dto.PrivacyAuthCreateRequest;
import com.fuyun.patient.dto.PrivacyMaskRuleUpdateRequest;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.entity.PrivacyAuth;
import com.fuyun.patient.enums.PrivacyAuthStatus;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.service.PrivacyService;
import com.fuyun.patient.service.impl.PrivacyAuthServiceImpl;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.PrivacyAuthVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import com.fuyun.patient.vo.UnmaskVO;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.enums.AuditActionType;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 隐私授权与明文查阅端点（M02 Spec §7 FU-M02-06）：GET/POST /privacy-auths、GET/PUT
 * /privacy-mask-rules、POST /privacy/unmask、GET /privacy-access-logs 六端点。
 *
 * <p>审计落点：POST /privacy-auths 与 PUT /privacy-mask-rules 挂 WRITE；POST /privacy/unmask 挂
 * SENSITIVE_QUERY（双留痕的审计侧，台账侧在 PrivacyServiceImpl 落 privacy_access_log）。
 * controller 禁业务逻辑与事务（A.1-8）：豁免校验/解密/落痕全在 service impl 方法级；
 * 授权出参的派生状态经 {@link PrivacyAuthServiceImpl#deriveStatus} 静态工具组装（零定时任务口径）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PrivacyController {

    private final IPrivacyAuthService privacyAuthService;

    private final PrivacyMaskService privacyMaskService;

    private final PrivacyService privacyService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param privacyAuthService 隐私授权服务（登记/清单查询），非空
     * @param privacyMaskService 脱敏引擎（规则清单/维护），非空
     * @param privacyService     明文查阅与留痕服务（唯一明文出口），非空
     */
    public PrivacyController(
            IPrivacyAuthService privacyAuthService,
            PrivacyMaskService privacyMaskService,
            PrivacyService privacyService) {
        this.privacyAuthService = privacyAuthService;
        this.privacyMaskService = privacyMaskService;
        this.privacyService = privacyService;
    }

    /**
     * 授权清单（GET /privacy-auths?patientId=，签署时序倒序；derivedStatus 为读侧派生态）。
     *
     * @param patientId 患者主索引（查询参数，必填）
     * @return 授权出参清单（无授权为空清单）；200
     */
    @GetMapping("/privacy-auths")
    public List<PrivacyAuthVO> auths(@RequestParam long patientId) {
        return privacyAuthService.listByPatient(patientId).stream()
                .map(auth -> new PrivacyAuthVO(
                        auth.getId(),
                        auth.getPatientId(),
                        auth.getAuthType(),
                        auth.getAuthBasis(),
                        auth.getScope(),
                        auth.getSignedAt(),
                        auth.getValidTo(),
                        PrivacyAuthServiceImpl.deriveStatus(auth)))
                .toList();
    }

    /**
     * 授权登记（POST /privacy-auths，WRITE 审计）：知情同意外的授权类型统一登记入口
     * （建档知情同意走注册事务内 recordInformedConsent）。
     *
     * @param request 登记请求（@Valid，类型词表/依据引用必填）
     * @return 授权出参（派生状态按登记值即时派生）；201
     */
    @PostMapping("/privacy-auths")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public PrivacyAuthVO createAuth(@Valid @RequestBody PrivacyAuthCreateRequest request) {
        PrivacyAuth auth = new PrivacyAuth();
        auth.setPatientId(request.patientId());
        auth.setAuthType(request.authType());
        auth.setAuthBasis(request.authBasis());
        auth.setScope(request.scope());
        // 签署时刻空=落当前时刻（与建档知情同意同口径）；失效时刻空=长期有效（EXPIRED 读侧派生）
        auth.setSignedAt(
                request.signedAtIso() == null || request.signedAtIso().isBlank()
                        ? OffsetDateTime.now()
                        : OffsetDateTime.parse(request.signedAtIso()));
        auth.setValidTo(
                request.validToIso() == null || request.validToIso().isBlank()
                        ? null
                        : OffsetDateTime.parse(request.validToIso()));
        auth.setStatus(PrivacyAuthStatus.EFFECTIVE.name());
        // 数据库写操作：授权行落库（主键 ASSIGN_ID 插入期回填）
        privacyAuthService.save(auth);
        return new PrivacyAuthVO(
                auth.getId(),
                auth.getPatientId(),
                auth.getAuthType(),
                auth.getAuthBasis(),
                auth.getScope(),
                auth.getSignedAt(),
                auth.getValidTo(),
                PrivacyAuthServiceImpl.deriveStatus(auth));
    }

    /**
     * 脱敏规则清单（GET /privacy-mask-rules；exemptRoles 拆分清单输出）。
     *
     * @return 规则出参清单（种子固定 5 行量级）；200
     */
    @GetMapping("/privacy-mask-rules")
    public List<PrivacyMaskRuleVO> maskRules() {
        return privacyMaskService.listRules();
    }

    /**
     * 脱敏规则维护（PUT /privacy-mask-rules/{ruleCode}，WRITE 审计；部分更新语义，
     * 落库后经引擎每请求加载即时生效）。
     *
     * @param ruleCode 规则编码（路径变量，业务唯一）
     * @param request  维护请求（@Valid，非空字段覆盖库值）
     * @return 维护后规则出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1021（404 规则编码无命中）
     */
    @PutMapping("/privacy-mask-rules/{ruleCode}")
    @AuditLog(actionType = AuditActionType.WRITE)
    public PrivacyMaskRuleVO updateRule(
            @PathVariable String ruleCode, @Valid @RequestBody PrivacyMaskRuleUpdateRequest request) {
        return privacyMaskService.updateRule(ruleCode, request);
    }

    /**
     * 明文查阅（POST /privacy/unmask，SENSITIVE_QUERY 审计）：全仓唯一明文出口，
     * 角色豁免校验 403 前置，成功由审计行 + 查阅台账行双留痕。
     *
     * @param request 查阅请求（@Valid，字段词表/purpose 必填）
     * @return 明文值集；200
     * @throws com.fuyun.common.exception.BizException PAT-1018（403 无豁免角色）/ PAT-1001（404 档案不存在）
     */
    @PostMapping("/privacy/unmask")
    @AuditLog(actionType = AuditActionType.SENSITIVE_QUERY)
    public UnmaskVO unmask(@Valid @RequestBody UnmaskRequest request) {
        return privacyService.unmask(request);
    }

    /**
     * 查阅台账分页（GET /privacy-access-logs，等保审计主检索）。
     *
     * @param patientId 患者过滤（可空=全量）
     * @param page      页码（0 基，缺省 0）
     * @param size      单页条数（1-200，越界收敛）
     * @return 台账分页；200
     */
    @GetMapping("/privacy-access-logs")
    public PageResult<PrivacyAccessLogVO> accessLogs(
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PrivacyAccessLogQuery query = new PrivacyAccessLogQuery(patientId, page, Math.min(Math.max(size, 1), 200));
        return privacyService.listAccessLogs(query.patientId(), query.page(), query.size());
    }
}

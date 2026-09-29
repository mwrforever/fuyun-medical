package com.fuyun.patient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.PrivacyAccessLogQuery;
import com.fuyun.patient.dto.PrivacyAuthCreateRequest;
import com.fuyun.patient.dto.PrivacyMaskRuleUpdateRequest;
import com.fuyun.patient.dto.UnmaskRequest;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.IPrivacyMaskService;
import com.fuyun.patient.service.IPrivacyService;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.PrivacyAuthVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import com.fuyun.patient.vo.UnmaskVO;
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
 * 隐私授权与明文查阅端点（M02 Spec §7 FU-M02-06）：GET/POST /privacy-auths、GET/PUT
 * /privacy-mask-rules、POST /privacy/unmask、GET /privacy-access-logs 六端点。
 *
 * <p>审计落点：POST /privacy-auths 与 PUT /privacy-mask-rules 挂 WRITE；POST /privacy/unmask 挂
 * SENSITIVE_QUERY（双留痕的审计侧，台账侧在 PrivacyServiceImpl 落 privacy_access_log）。
 * 安全收口（SEC-01）：PUT /privacy-mask-rules 仅限 ADMIN 角色（PAT-1024 403，门禁在
 * PrivacyMaskServiceImpl.updateRule 方法首行），阻断非管理员改写 exemptRoles 自授豁免再经
 * unmask 提权解密的攻击链；读端点与 unmask 豁免链路不受影响。
 * controller 禁业务逻辑与事务（A.1-8）：豁免校验/解密/落痕、授权登记（实体组装/时刻派生/
 * 落库/Entity→VO 与派生状态组装）、规则维护 ADMIN 门禁与台账检索条件收敛（size 1-200）
 * 全在 service impl 方法级；controller 仅参数校验+服务调用+响应组装。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class PrivacyController {

    private final IPrivacyAuthService privacyAuthService;

    private final IPrivacyMaskService privacyMaskService;

    private final IPrivacyService privacyService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param privacyAuthService 隐私授权服务（登记/清单查询），非空
     * @param privacyMaskService 脱敏引擎（规则清单/维护），非空
     * @param privacyService     明文查阅与留痕服务（唯一明文出口），非空
     */
    public PrivacyController(
            IPrivacyAuthService privacyAuthService,
            IPrivacyMaskService privacyMaskService,
            IPrivacyService privacyService) {
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
        return privacyAuthService.listAuthVosByPatient(patientId);
    }

    /**
     * 授权登记（POST /privacy-auths，WRITE 审计）：知情同意外的授权类型统一登记入口
     * （建档知情同意走注册事务内 recordInformedConsent）；实体组装/时刻派生/落库/出参组装
     * 归 service（controller 仅校验+调用+响应）。
     *
     * @param request 登记请求（@Valid，类型词表/依据引用必填；时刻文本 ISO-8601 守卫在 service）
     * @return 授权出参（派生状态按登记值即时派生）；201
     * @throws com.fuyun.common.exception.BizException PAT-1023（400）signedAtIso/validToIso
     *                                                 非法 ISO 时刻文本（service 解析守卫）
     */
    @PostMapping("/privacy-auths")
    @ResponseStatus(HttpStatus.CREATED)
    @AuditLog(actionType = AuditActionType.WRITE)
    public PrivacyAuthVO createAuth(@Valid @RequestBody PrivacyAuthCreateRequest request) {
        return privacyAuthService.createAuth(request);
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
     * 落库后经引擎每请求加载即时生效）。SEC-01 安全收口：仅 ADMIN 角色可维护——若任意登录
     * 用户可改写 exemptRoles，即可自授豁免再经 unmask 提权解密，故非 ADMIN 一律拒绝
     * （ADMIN 门禁与拒绝 warn 留痕归 PrivacyMaskServiceImpl.updateRule 方法首行，
     * 拒绝由审计切面 FAIL 行留痕，与 unmask 403 同模式）。
     *
     * @param ruleCode 规则编码（路径变量，业务唯一）
     * @param request  维护请求（@Valid，非空字段覆盖库值）
     * @return 维护后规则出参；200
     * @throws com.fuyun.common.exception.BizException PAT-1024（403 非 ADMIN 角色，SEC-01
     *                                                 门禁在 service）/ PAT-1021（404 规则编码无命中）
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
     * 查阅台账分页（GET /privacy-access-logs，等保审计主检索；size 越界收敛 1-200 归 service，
     * controller 仅组装原始请求参数）。
     *
     * @param patientId 患者过滤（可空=全量）
     * @param page      页码（0 基，缺省 0）
     * @param size      单页条数（缺省 20，原始值直传 service 收敛 1-200）
     * @return 台账分页；200
     */
    @GetMapping("/privacy-access-logs")
    public PageResult<PrivacyAccessLogVO> accessLogs(
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return privacyService.listAccessLogs(new PrivacyAccessLogQuery(patientId, page, size));
    }
}

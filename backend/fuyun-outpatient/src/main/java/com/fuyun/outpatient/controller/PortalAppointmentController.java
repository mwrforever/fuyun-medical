package com.fuyun.outpatient.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.cache.PortalCredentialRateGuard;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.dto.CancelAppointmentRequest;
import com.fuyun.outpatient.dto.PortalAppointmentRequest;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IScheduleService;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientIdentityQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * portal 患者匿名预约端点（/api/v1/outpatient/portal/**，裁决 13 免登录白名单通道）：服务端经介质
 * 解析（就诊卡号/证件号 → patient/api PatientIdentityQuery）定 patientId 后进入统一预约主流程，
 * <b>不经 OperatorContextHolder</b>（操作者留痕取哨兵值 PORTAL）；portal 患者账号体系随 M18/P6
 * 完整化（P1 演示口径注记）；<b>限流/风控随 M18 注记</b>——EX-29 已加临时缓解（预约/退号链路
 * 证件号频控+单患者活跃预约上限，BE-A3-02 裁决③），M18 患者账号体系上线后由归属校验取代（本通道
 * 免登录，P1 依赖白名单最小暴露面）。
 * portal 退号端点随 Task 6 与退号四分支统一交付。职责边界：仅 @Valid 校验+介质解析+调用 service，
 * 禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "portal 匿名预约")
@Slf4j
@RestController
@RequestMapping("/api/v1/outpatient/portal")
@RequiredArgsConstructor
public class PortalAppointmentController {

    /** portal 介质类型开放面（词表外值显式 400；证件格式细则校验随 portal 前端显式规则，P-8） */
    private static final Set<String> PORTAL_CREDENTIAL_TYPES = Set.of("ID_CARD", "VISIT_CARD");

    private final IScheduleService scheduleService;

    private final IAppointmentService appointmentService;

    private final PatientIdentityQuery patientIdentityQuery;

    /** portal 匿名预约证件号频控守卫（EX-29 临时缓解②，BE-A3-02 裁决③）：判定与计数下沉本守卫，
     * 控制器仅编排（B.1 分层）；M18 患者账号体系上线后由归属校验取代 */
    private final PortalCredentialRateGuard credentialRateGuard;

    /**
     * 可约号源查询（免登录聚合面）：复用号源余量查询（ACTIVE 且有余量行，slot_start 升序）。
     *
     * @param deptCode 开诊科室编码，必填
     * @param date     排班日期，必填
     * @return 可约池行出参集（含 remaining），非空；无可约号源返回空列表
     */
    @Operation(summary = "portal 可约号源查询（免登录）")
    @GetMapping("/schedules")
    public List<NumberPoolVO> schedules(
            @RequestParam String deptCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scheduleService.availablePools(deptCode, date, null);
    }

    /**
     * portal 预约（免登录）：介质解析换 patientId（PAT-1001 无命中/已失效由 patient 契约异常透出），
     * channel 固定 PORTAL 进入统一预约主流程（爽约限约/限购/冻结拦截全渠道一致）。
     * 临时缓解（EX-29，BE-A3-02 裁决③）：②证件号频控——冷却期内前置 429 OP-1023 拒绝（不触达
     * 介质解析，枚举面收敛），解析未命中计入连续失败计数、成功清零（判定与计数下沉
     * PortalCredentialRateGuard）；①单患者活跃预约上限由 service.book 内判定（B.1：查数下沉），
     * 超限 409 OP-1022 透传。M18 患者账号体系上线后由归属校验取代。
     *
     * @param request portal 预约请求（credentialType/credentialNo/poolId），非空
     * @return 预约单出参（RESERVED+payDeadline 支付时限占位），非空
     * @throws BizException OP-1023（429 频控冷却中）/ OP-1022（409 活跃预约数超上限，服务层
     *                      判定透传）时触发；建议处理策略：冷却提示稍后重试，超限引导先退号或到院办理
     */
    @Operation(summary = "portal 预约（免登录）")
    @PostMapping("/appointments")
    public AppointmentVO book(@Valid @RequestBody PortalAppointmentRequest request) {
        // 介质类型显式格式校验（W-22⑦ 口径）：词表外 400，禁裸透传
        if (!PORTAL_CREDENTIAL_TYPES.contains(request.credentialType())) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "介质类型仅支持 ID_CARD/VISIT_CARD：" + request.credentialType());
        }
        // 临时缓解②：冷却期前置拒绝（429 OP-1023，判定归守卫）——匿名枚举试错在介质解析前即被阻断
        credentialRateGuard.checkNotCoolingDown(request.credentialType(), request.credentialNo());
        // 介质解析（M02 身份解析面）：明文仅本调用生命周期内存活，禁入日志（敏感字段脱敏红线）；
        // 解析未命中（PAT-1001 与档案不符）计入该证件号连续失败计数（临时缓解②的计数编排面）
        long patientId;
        try {
            patientId = patientIdentityQuery.resolveActivePatientId(request.credentialType(), request.credentialNo());
        } catch (BizException e) {
            if (PatientErrorCode.PATIENT_NOT_FOUND == e.getErrorCode()) {
                // 频控判定与 Redis 计数归守卫（B.1 下沉），本层只编排「与档案不符即计数」
                credentialRateGuard.recordResolutionFailure(request.credentialType(), request.credentialNo());
            }
            throw e;
        }
        // 解析成功清零连续失败计数（「连续」语义的成功打断面）；临时缓解①活跃预约上限查数与
        // 判定在 service.book 统一入口内执行（B.1 分层），本层仅编排
        credentialRateGuard.clearFailureCount(request.credentialType(), request.credentialNo());
        return appointmentService.book(
                new AppointmentCreateRequest(patientId, request.poolId(), ApptChannel.PORTAL.getCode()));
    }

    /**
     * portal 退号（免登录，Task 5 移交随退号四分支统一交付；BUG-01 归属校验收口）：与工作站退号
     * 共用四分支语义（线上退号时限 OP-1010/已付退费回执驱动终态），但必须携带介质凭证——单号
     * 顺序流水高度可枚举，服务端解析 patientId 后由服务层比对单据归属（不匹配 403 OP-1021，
     * 阻断匿名遍历单号退他人号源）；匿名链路不经审计切面（裁决 13——操作者留痕取哨兵值
     * PORTAL；限流/风控随 M18 注记）。临时缓解②（EX-29）退号侧收口：证件号频控与 book 同链路
     * 同口径——冷却期前置 429 OP-1023 拒绝（不触达介质解析）、解析未命中计入连续失败计数、
     * 解析成功清零，堵「仅预约侧有频控、经退号端点裸解析」的绕过面；M18 后由归属校验取代。
     *
     * @param no      预约单业务号（路径参数）
     * @param request 退号请求（reason 必填留痕；credentialType/credentialNo 介质凭证本链路必填），非空
     * @return 预约单出参（分支 1=CANCELLED；分支 2=RESERVED 待退费回执），非空
     * @throws BizException OP-1023（429 证件号频控冷却中）/ OP-1021（403 介质解析患者与单据
     *                      归属不符，服务层判定透传）时触发；建议处理策略：冷却提示稍后重试，
     *                      归属不符引导核对持卡人或转人工窗口办理
     */
    @Operation(summary = "portal 退号（免登录）")
    @PostMapping("/appointments/{no}/cancel")
    public AppointmentVO cancel(@PathVariable("no") String no, @Valid @RequestBody CancelAppointmentRequest request) {
        // 介质凭证显式校验（BUG-01）：DTO 与工作站鉴权链路共用禁加 Bean Validation 必填，本免登录
        // 链路显式拒空值与词表外值（W-22⑦ 口径）；先判空再 contains——Set.of 对 null 元素抛 NPE
        if (request.credentialType() == null
                || request.credentialType().isBlank()
                || request.credentialNo() == null
                || request.credentialNo().isBlank()
                || !PORTAL_CREDENTIAL_TYPES.contains(request.credentialType())) {
            throw new BizException(
                    OutpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "退号介质凭证必填且类型仅支持 ID_CARD/VISIT_CARD");
        }
        // 临时缓解②（EX-29）退号侧收口：退号与预约同链路同频控口径——冷却期前置拒绝（429 OP-1023，
        // 判定归守卫），堵「预约侧有频控、经退号端点裸解析」的证件号有效性 oracle 绕过
        credentialRateGuard.checkNotCoolingDown(request.credentialType(), request.credentialNo());
        // 介质解析（M02 身份解析面，与 book 同型）：明文仅本调用生命周期内存活，禁入日志（脱敏红线）；
        // 解析未命中（PAT-1001 与档案不符）计入该证件号连续失败计数（与 book 同款计数编排）
        long patientId;
        try {
            patientId = patientIdentityQuery.resolveActivePatientId(request.credentialType(), request.credentialNo());
        } catch (BizException e) {
            if (PatientErrorCode.PATIENT_NOT_FOUND == e.getErrorCode()) {
                // 频控判定与 Redis 计数归守卫（B.1 下沉），本层只编排「与档案不符即计数」
                credentialRateGuard.recordResolutionFailure(request.credentialType(), request.credentialNo());
            }
            throw e;
        }
        // 解析成功清零连续失败计数（与 book 同语义：介质解析成功=自证持卡，打断连续失败计数）
        credentialRateGuard.clearFailureCount(request.credentialType(), request.credentialNo());
        // 受理留痕（info）：凭证只打类型与长度摘要，禁任何明文片段（患者敏感字段脱敏红线）
        log.info(
                "portal 免登录退号受理：apptNo={}，介质类型={}，介质号长度={}",
                no,
                request.credentialType(),
                request.credentialNo().length());
        // 归属比对与取消在服务层同事务完成（TOCTOU 收口），携解析患者委托三参 cancel
        return appointmentService.cancel(no, request.reason(), patientId);
    }
}

package com.fuyun.outpatient.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.dto.PortalAppointmentRequest;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IScheduleService;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.patient.api.PatientIdentityQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * portal 患者匿名预约端点（/api/v1/outpatient/portal/**，裁决 13 免登录白名单通道）：服务端经介质
 * 解析（就诊卡号/证件号 → patient/api PatientIdentityQuery）定 patientId 后进入统一预约主流程，
 * <b>不经 OperatorContextHolder</b>（操作者留痕取哨兵值 PORTAL）；portal 患者账号体系随 M18/P6
 * 完整化（P1 演示口径注记），<b>限流/风控随 M18 注记</b>（本通道免登录，P1 依赖白名单最小暴露面）。
 * portal 退号端点随 Task 6 与退号四分支统一交付。职责边界：仅 @Valid 校验+介质解析+调用 service，
 * 禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "portal 匿名预约")
@RestController
@RequestMapping("/api/v1/outpatient/portal")
@RequiredArgsConstructor
public class PortalAppointmentController {

    /** portal 介质类型开放面（词表外值显式 400；证件格式细则校验随 portal 前端显式规则，P-8） */
    private static final Set<String> PORTAL_CREDENTIAL_TYPES = Set.of("ID_CARD", "VISIT_CARD");

    private final IScheduleService scheduleService;

    private final IAppointmentService appointmentService;

    private final PatientIdentityQuery patientIdentityQuery;

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
     *
     * @param request portal 预约请求（credentialType/credentialNo/poolId），非空
     * @return 预约单出参（RESERVED+payDeadline 支付时限占位），非空
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
        // 介质解析（M02 身份解析面）：明文仅本调用生命周期内存活，禁入日志（敏感字段脱敏红线）
        long patientId = patientIdentityQuery.resolveActivePatientId(request.credentialType(), request.credentialNo());
        return appointmentService.book(
                new AppointmentCreateRequest(patientId, request.poolId(), ApptChannel.PORTAL.getCode()));
    }
}

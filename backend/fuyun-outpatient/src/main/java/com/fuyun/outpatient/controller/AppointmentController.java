package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.VisitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预约挂号端点（/api/v1/outpatient 前缀，M03 Spec §7：动作子路径 POST 形态）：统一预约/当日挂号与
 * 预约取号。退号/改期随 Task 6 交付。挂号类写端点不在审计切面登记清单（退号/加号/停诊等才审计，
 * Global Constraints 审计口径），操作者留痕经 created_by/updated_by 承载。职责边界：仅 @Valid 校验
 * +调用 service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "预约挂号")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class AppointmentController {

    private final IAppointmentService appointmentService;

    /**
     * 统一预约/当日挂号（多渠道统一入口）：WINDOW/KIOSK 一步直达 TAKEN（同事务签发 visit），
     * PORTAL 占位 RESERVED（支付时限 15 分钟，超时经延迟任务释放）。
     *
     * @param request 预约请求，非空；格式约束由 JSR-303 校验
     * @return 预约单出参（apptNo/status/visitId/payDeadline），非空
     */
    @Operation(summary = "统一预约/当日挂号")
    @PostMapping("/appointments")
    public AppointmentVO book(@Valid @RequestBody AppointmentCreateRequest request) {
        return appointmentService.book(request);
    }

    /**
     * 预约取号（RESERVED→TAKEN，签发 visit）：支付时限内放行，超时占位拒 OP-1008 引导窗口处置；
     * TAKEN 态重复取号幂等返回既有 visit。
     *
     * @param no 预约单业务号（路径参数）
     * @return 就诊记录出参（visitId），非空
     */
    @Operation(summary = "预约取号")
    @PostMapping("/appointments/{no}/take")
    public VisitVO take(@PathVariable("no") String no) {
        return appointmentService.take(no);
    }
}

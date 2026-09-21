package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.dto.CancelAppointmentRequest;
import com.fuyun.outpatient.dto.RescheduleRequest;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
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
 * 预约挂号端点（/api/v1/outpatient 前缀，M03 Spec §7：动作子路径 POST 形态）：统一预约/当日挂号、
 * 预约取号、退号与改期（Task 6——退号四分支退费联动、改期先占新后退旧）。退号/改期为跨资金终态
 * 动作全量 @AuditLog(WRITE) 留痕（Global Constraints 审计口径；portal 匿名通道退号不经本控制器，
 * 留痕经服务层哨兵值承载）。操作者留痕经审计切面与 updated_by 承载。职责边界：仅 @Valid 校验
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

    /**
     * 退号（退号退费联动四分支，Task 6）：未支付时限内免退费直取消；已支付走 M13 免审退费保持
     * 占位待 billing.refund.approved 回执；已报到/已接诊拒线上退 OP-1010（窗口「未诊即退」线下承载）。
     *
     * @param no      预约单业务号（路径参数）
     * @param request 退号请求（reason 必填留痕），非空
     * @return 预约单出参（分支 1=CANCELLED；分支 2/3=原态待回执），非空
     */
    @Operation(summary = "预约退号")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/appointments/{no}/cancel")
    public AppointmentVO cancel(@PathVariable("no") String no, @Valid @RequestBody CancelAppointmentRequest request) {
        return appointmentService.cancel(no, request.reason());
    }

    /**
     * 改期（退旧号新，reschedule_of 链；先占新后退旧防两头空，Task 6）：仅未支付占位单可改期，
     * 已支付单引导退号退费链。
     *
     * @param no      预约单业务号（路径参数）
     * @param request 改期请求（newPoolId 必填），非空
     * @return 新预约单出参（RESERVED），非空
     */
    @Operation(summary = "预约改期")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/appointments/{no}/reschedule")
    public AppointmentVO reschedule(@PathVariable("no") String no, @Valid @RequestBody RescheduleRequest request) {
        return appointmentService.reschedule(no, request);
    }
}

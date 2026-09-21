package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.FinishVisitRequest;
import com.fuyun.outpatient.dto.OrderCreateRequest;
import com.fuyun.outpatient.service.IClinicOrderService;
import com.fuyun.outpatient.service.IVisitService;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import com.fuyun.outpatient.vo.DoctorQueueItemVO;
import com.fuyun.outpatient.vo.VisitVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门诊医生站端点（/api/v1/outpatient 前缀，M03 Spec :152/:154：动作子路径 POST 形态）：接诊/诊毕/
 * 候诊列表/开单。接诊/诊毕/开单为诊疗关键动作全量 @AuditLog(WRITE) 留痕（Global Constraints 审计
 * 口径）；操作者留痕经 OperatorContextHolder（接诊医生身份）。职责边界：仅 @Valid 校验+调用
 * service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "门诊医生站")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class VisitController {

    private final IVisitService visitService;

    private final IClinicOrderService clinicOrderService;

    /**
     * 接诊（叫号≠接诊）：票 CALLED→SERVING+serve_time 联动，visit WAITING→IN_CONSULT+
     * admitted_at 回填（状态机单点校验+每迁必记，红线 5）。
     *
     * @param visitId 就诊号，非空
     * @return 接诊后就诊出参（status=IN_CONSULT），非空
     */
    @Operation(summary = "接诊")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/visits/{visitId}/admit")
    public VisitVO admit(@PathVariable String visitId) {
        return visitService.admit(visitId);
    }

    /**
     * 诊毕：离院去向词表校验+在途单据显式确认校验+visit→FINISHED+visit.finished 发布。
     *
     * @param visitId 就诊号，非空
     * @param request 诊毕请求（disposition/explicitConfirm），非空
     * @return 诊毕后就诊出参（status=FINISHED），非空
     */
    @Operation(summary = "诊毕")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/visits/{visitId}/finish")
    public VisitVO finish(@PathVariable String visitId, @Valid @RequestBody FinishVisitRequest request) {
        return visitService.finish(visitId, request);
    }

    /**
     * 医生站候诊列表：本队列 WAITING/CALLED 票+脱敏摘要+过敏声明位（P1 恒 false）。
     *
     * @param deptCode 队列标识（=dept_code），非空
     * @param doctorId 医生 id，非空
     * @return 候诊列表行（优先级降序）；空队列返回空列表
     */
    @Operation(summary = "医生站候诊列表")
    @GetMapping("/doctor/patient-queue")
    public List<DoctorQueueItemVO> patientQueue(@RequestParam String deptCode, @RequestParam String doctorId) {
        return visitService.patientQueue(deptCode, doctorId);
    }

    /**
     * 医生站开单：visit 终态守卫+执业授权强校验+CREATED 落库+order.created 发布（AFTER_COMMIT）。
     *
     * @param visitId 就诊号，非空
     * @param request 开单请求（orderType/items），非空
     * @return 申请单出参（status=CREATED，含明细行），非空
     */
    @Operation(summary = "医生站开单")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/visits/{visitId}/orders")
    public ClinicOrderVO createOrder(@PathVariable String visitId, @Valid @RequestBody OrderCreateRequest request) {
        return clinicOrderService.create(visitId, request);
    }
}

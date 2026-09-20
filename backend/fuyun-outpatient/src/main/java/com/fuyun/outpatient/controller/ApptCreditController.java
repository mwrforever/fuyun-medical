package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.CreditReleaseRequest;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.vo.ApptCreditVO;
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
 * 爽约信用管理端点（/api/v1/outpatient 前缀，M03 Spec §7/§9：信用手工解除为独立权限点且全量
 * 审计，Task 6）：患者维度信用台账查询与限约手工解除（restrict_to 提前至今日-1+release_reason
 * 留痕）。职责边界：仅 @Valid 校验+调用 service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "预约信用管理")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class ApptCreditController {

    private final IAppointmentService appointmentService;

    /**
     * 爽约信用记录查询（按患者维度，id 降序最新在前）。
     *
     * @param patientId 患者主索引，必填
     * @return 信用记录出参列表；无记录返回空列表
     */
    @Operation(summary = "爽约信用记录查询")
    @GetMapping("/appt-credits")
    public List<ApptCreditVO> credits(@RequestParam long patientId) {
        return appointmentService.creditsByPatient(patientId);
    }

    /**
     * 爽约限约手工解除（跨患者权益动作，全量审计留痕）：仅在效限约可解除，解除后限制即时失效。
     *
     * @param id      信用记录主键（路径参数）
     * @param request 解除请求（reason 必填留痕），非空
     * @return 解除后的信用记录出参，非空
     */
    @Operation(summary = "爽约限约手工解除")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/appt-credits/{id}/release")
    public ApptCreditVO release(@PathVariable("id") long id, @Valid @RequestBody CreditReleaseRequest request) {
        return appointmentService.releaseCredit(id, request.reason());
    }
}

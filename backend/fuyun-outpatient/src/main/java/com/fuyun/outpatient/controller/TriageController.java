package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.CheckInRequest;
import com.fuyun.outpatient.dto.TriageAdjustRequest;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.vo.QueueTicketVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分诊台端点（/api/v1/outpatient 前缀，M03 Spec §7：动作子路径 POST 形态）：报到入队与二次分诊/
 * 调级/跨队列转接。报到/二次分诊为分诊关键动作全量 @AuditLog(WRITE) 留痕（Global Constraints
 * 审计口径）；操作者留痕经 OperatorContextHolder（分诊护士身份）。职责边界：仅 @Valid 校验+调用
 * service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "分诊台")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class TriageController {

    private final ITriageService triageService;

    /**
     * 分诊报到（visit REGISTERED→WAITING+建票入队）：预约已 TAKEN 直接可报到；重复报到/终态
     * OP-1011 拒绝。
     *
     * @param request 报到请求（visitId/stationId/老幼残因子），非空
     * @return 候诊票据出参（ticketNo/priorityScore/脱敏姓名），非空
     */
    @Operation(summary = "分诊报到")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/triage/check-in")
    public QueueTicketVO checkIn(@Valid @RequestBody CheckInRequest request) {
        return triageService.checkIn(request);
    }

    /**
     * 二次分诊/调级/跨队列转接（action 词表分流）：调级重排不改号（过号降级重排不改号 Spec :106），
     * 转队列旧队放票新队建票。
     *
     * @param request 分诊调整请求（action/targetQueue/doctorId/triageLevel/priorityFactors），非空
     * @return 调整后票据出参（转队列为新票），非空
     */
    @Operation(summary = "二次分诊/调级/转队列")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/triage/adjust")
    public QueueTicketVO adjust(@Valid @RequestBody TriageAdjustRequest request) {
        return triageService.adjust(request);
    }
}

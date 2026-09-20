package com.fuyun.outpatient.controller;

import com.fuyun.outpatient.dto.QueueCallRequest;
import com.fuyun.outpatient.service.ITriageService;
import com.fuyun.outpatient.vo.QueueTicketVO;
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
 * 候诊队列端点（/api/v1/outpatient 前缀，M03 Spec §7：动作子路径 POST 形态）：叫号、过号、重呼
 * 与队列 REST 快照（快照=实时 WS 推送之外的拉取通道，双通道之一 Spec :153）。叫号/过号/重呼为
 * 分诊关键动作全量 @AuditLog(WRITE) 留痕（Global Constraints 审计口径）；快照为只读面免审计。
 * 职责边界：仅 @Valid 校验+调用 service+编排响应，禁业务逻辑与事务（宪法 B.1/A.1-8）。
 */
@Tag(name = "候诊队列")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class QueueController {

    private final ITriageService triageService;

    /**
     * 叫号（ZSET 优先级序原子出队+双 topic WS 推送）：空队/无可叫票返回 200 空语义（null 直出）。
     * 叫号≠接诊——visit 保持 WAITING（接诊由 /visits/{visitId}/admit 承载）。
     *
     * @param request 叫号请求（deptCode/doctorId），非空
     * @return 叫中票据出参；队列空为 null（200 空响应体）
     */
    @Operation(summary = "候诊叫号")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/queue/call")
    public QueueTicketVO call(@Valid @RequestBody QueueCallRequest request) {
        return triageService.call(request);
    }

    /**
     * 过号（CALLED→PASSED 降级重入，票号不变 Spec :106）。
     *
     * @param id 票据主键（路径参数）
     * @return 过号票据出参（PASSED），非空
     */
    @Operation(summary = "过号")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/queue/tickets/{id}/pass")
    public QueueTicketVO pass(@PathVariable("id") long id) {
        return triageService.pass(id);
    }

    /**
     * 重呼（PASSED→CALLED 重复叫，called_count 累加并重复推送）。
     *
     * @param id 票据主键（路径参数）
     * @return 重呼票据出参（CALLED），非空
     */
    @Operation(summary = "重呼")
    @AuditLog(actionType = AuditActionType.WRITE)
    @PostMapping("/queue/tickets/{id}/recall")
    public QueueTicketVO recall(@PathVariable("id") long id) {
        return triageService.recall(id);
    }

    /**
     * 队列 REST 快照（脱敏出网：patientName 为 patient 侧掩码展示名，无证件号字段）：按优先级分
     * 降序、同分按建行时间升序。
     *
     * @param queueId 队列标识（=dept_code，路径参数）
     * @param status  状态过滤词表值（TicketStatus code），可空
     * @return 票据出参列表；空队列为空列表
     */
    @Operation(summary = "队列快照")
    @GetMapping("/queues/{queueId}/tickets")
    public List<QueueTicketVO> snapshot(
            @PathVariable("queueId") String queueId, @RequestParam(value = "status", required = false) String status) {
        return triageService.snapshot(queueId, status);
    }
}

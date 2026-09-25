package com.fuyun.pharmacy.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.ReviewDecisionRequest;
import com.fuyun.pharmacy.service.IMedicationReviewService;
import com.fuyun.pharmacy.vo.ReviewTaskVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 住院用药审方工作台端点（/api/v1/pharmacy/review-tasks，GC13 资源复数；任务 id 路径参数
 * long 承载、出参经全局 Long→String 序列化 string 化）：列表读面不挂审计（DispenseController
 * 先例），通过/驳回为法定留痕写操作全量审计（WRITE）。调用方：M06 药师工作站审方台。
 */
@Tag(name = "住院用药审方")
@RestController
@RequiredArgsConstructor
public class ReviewTaskController {

    private final IMedicationReviewService medicationReviewService;

    /**
     * 审方工作台列表（先到先审 FIFO）：医嘱号/患者摘要（号面）/药品明细/申请科室回显。
     *
     * @param status 状态过滤（PENDING/APPROVED/REJECTED），可空
     * @param page   页码（0 基，缺省 0）
     * @param size   单页条数（缺省 10）
     * @return 分页出参（content 可为空清单）
     */
    @Operation(summary = "审方工作台列表")
    @GetMapping("/api/v1/pharmacy/review-tasks")
    public PageResult<ReviewTaskVO> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return medicationReviewService.list(status, page, size);
    }

    /**
     * 审方通过（PENDING→APPROVED）：发布 pharmacy.medication-order.audit-completed 回执，
     * M04 置医嘱可执行。
     *
     * @param id  任务 id（路径参数）
     * @param req 决策入参（意见可选），可缺省（body 缺省即 null 安全跳过）
     */
    @Operation(summary = "审方通过")
    @PostMapping("/api/v1/pharmacy/review-tasks/{id}/approve")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void approve(@PathVariable("id") long id, @Valid @RequestBody(required = false) ReviewDecisionRequest req) {
        medicationReviewService.approve(id, req == null ? null : req.opinion());
    }

    /**
     * 审方驳回（PENDING→REJECTED，意见必填——缺/空白 PH-1020）：发布 pharmacy.medication-order.
     * audit-rejected 回执，M04 置驳回态走医生站修改重提。
     *
     * @param id  任务 id（路径参数）
     * @param req 决策入参（意见必填由服务端守卫——400 先拦会破坏 PH-1020 冻结语义）
     */
    @Operation(summary = "审方驳回")
    @PostMapping("/api/v1/pharmacy/review-tasks/{id}/reject")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void reject(@PathVariable("id") long id, @Valid @RequestBody(required = false) ReviewDecisionRequest req) {
        medicationReviewService.reject(id, req == null ? null : req.opinion());
    }
}

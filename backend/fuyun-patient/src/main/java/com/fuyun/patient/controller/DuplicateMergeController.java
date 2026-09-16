package com.fuyun.patient.controller;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.dto.ExcludeRequest;
import com.fuyun.patient.dto.MergeCreateRequest;
import com.fuyun.patient.service.IMergeRecordService;
import com.fuyun.patient.service.IPossibleDuplicateService;
import com.fuyun.patient.vo.MergeRecordVO;
import com.fuyun.patient.vo.PossibleDuplicateVO;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.enums.AuditActionType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重复治理与合并端点（M02 Spec §7：GET /possible-duplicates、POST exclude、POST /merges、
 * approve、split 五端点；合并/拆分仅限指定权限角色——RBAC 拦截随 P1 鉴权接入，本 PR 审计留痕先行）。
 *
 * <p>controller 禁业务逻辑与事务（A.1-8）：事务边界在 service impl 方法级；WRITE 审计由 M01 切面承载
 * （@AuditLog 注解 + AuditLogAspect 上下文内拦截）。
 */
@RestController
@RequestMapping("/api/v1/patient")
public class DuplicateMergeController {

    private final IPossibleDuplicateService duplicateService;

    private final IMergeRecordService mergeRecordService;

    /**
     * 构造器注入（A.1-7），装配归 PatientWebConfig @Import。
     *
     * @param duplicateService  疑似重复治理服务，非空
     * @param mergeRecordService 合并/拆分状态机服务，非空
     */
    public DuplicateMergeController(
            IPossibleDuplicateService duplicateService, IMergeRecordService mergeRecordService) {
        this.duplicateService = duplicateService;
        this.mergeRecordService = mergeRecordService;
    }

    /**
     * 待审列表（GET /possible-duplicates）。
     *
     * @param status 状态过滤（缺省 PENDING；ALL=不过滤）
     * @param page   0 基页码
     * @param size   单页条数（1-200，越界收敛）
     * @return 分页出参；200
     */
    @GetMapping("/possible-duplicates")
    public PageResult<PossibleDuplicateVO> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return duplicateService.list(status, page, Math.min(Math.max(size, 1), 200));
    }

    /**
     * 排除待审对（POST /possible-duplicates/{id}/exclude；WRITE 审计）。
     *
     * @param id      待审行 id
     * @param request 排除理由（@Valid 非空）
     */
    @PostMapping("/possible-duplicates/{id}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditLog(actionType = AuditActionType.WRITE)
    public void exclude(@PathVariable long id, @Valid @RequestBody ExcludeRequest request) {
        duplicateService.exclude(id, request.note());
    }

    /**
     * 发起合并（POST /merges；WRITE 审计；双人角色第一步）。
     *
     * @param request 发起请求（@Valid）
     * @return 合并记录出参；200
     */
    @PostMapping("/merges")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MergeRecordVO create(@Valid @RequestBody MergeCreateRequest request) {
        return mergeRecordService.create(request);
    }

    /**
     * 审批并执行合并（POST /merges/{id}/approve；WRITE 审计；双人角色第二步）。
     *
     * @param id 合并记录 id
     * @return 合并记录出参；200
     */
    @PostMapping("/merges/{id}/approve")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MergeRecordVO approve(@PathVariable long id) {
        return mergeRecordService.approve(id, currentOperator());
    }

    /**
     * 拆分恢复（POST /merges/{id}/split；WRITE 审计）。
     *
     * @param id 合并记录 id
     * @return 合并记录出参；200
     */
    @PostMapping("/merges/{id}/split")
    @AuditLog(actionType = AuditActionType.WRITE)
    public MergeRecordVO split(@PathVariable long id) {
        // 拆分原因以固定文案留痕：Spec 未定义 split 请求体，不为未定义交互造契约（前端工作台需要自填理由时再扩展）
        return mergeRecordService.split(id, "操作员发起拆分");
    }

    /**
     * 当前操作人（审计与双人角色比对锚点）。
     *
     * @return 操作人标识；未登录上下文回退 system（IT 场景）
     */
    private String currentOperator() {
        String operator = OperatorContextHolder.get();
        return operator == null ? "system" : operator;
    }
}

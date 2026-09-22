package com.fuyun.nursing.controller;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.dto.NursingTaskCancelRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理任务端点（/api/v1/nursing/tasks，GC13 资源复数 + 动作子路径 POST）。P1 最小载体四端点：
 * 创建 / 病区清单 / 完成 / 取消；巡视打卡走 PDA 面 {@code POST /api/v1/nursing/pda/patrol}
 * （Task 10 落点，禁在此重复暴露）；任务工作台（分组/认领/模板批量生成）归 P2 不设端点。
 * 类级 @RequestMapping 不承载（WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "护理任务")
@RestController
@RequiredArgsConstructor
public class NursingTaskController {

    private final INursingTaskService taskService;

    /**
     * 护理任务创建（手工开立；默认 PENDING 态，发布 nursing.task.created）。
     *
     * @param req 创建入参，非空
     * @return 任务出参（PENDING 态）
     */
    @Operation(summary = "护理任务创建（手工开立）")
    @PostMapping("/api/v1/nursing/tasks")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingTaskVO create(@Valid @RequestBody NursingTaskCreateRequest req) {
        return taskService.create(req);
    }

    /**
     * 病区任务清单（wardId 必选，status/date 可选，计划时间升序；读时惰性逾期判定）。
     *
     * @param wardId 病区编码，必填
     * @param status 状态过滤 code（TaskStatus 词表，可空，非法值 NS-1019）
     * @param date   计划日期过滤（ISO yyyy-MM-dd，可空）
     * @return 任务出参清单（计划时间升序）
     */
    @Operation(summary = "病区任务清单")
    @GetMapping("/api/v1/nursing/tasks")
    public List<NursingTaskVO> list(
            @RequestParam("wardId") String wardId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        // 状态 code 显式格式校验（W-22⑦ 禁裸转换——Spring 枚举直绑词表外值会被吞成通用 400，此处显式 NS-1019）
        TaskStatus statusEnum = status == null ? null : TaskStatus.fromCode(status);
        if (status != null && statusEnum == null) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "任务状态 code 非法：" + status);
        }
        return taskService.list(wardId, statusEnum, date);
    }

    /**
     * 护理任务完成（在途态 → COMPLETED，completedAt 盖章并发布完成事件）。
     *
     * @param taskNo 任务业务号（路径参数）
     * @return 完成后任务出参
     */
    @Operation(summary = "护理任务完成")
    @PostMapping("/api/v1/nursing/tasks/{taskNo}/complete")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingTaskVO complete(@PathVariable("taskNo") String taskNo) {
        return taskService.complete(taskNo);
    }

    /**
     * 护理任务取消（在途态 → CANCELLED，原因强制留痕并发布完成事件 status=CANCELLED）。
     *
     * @param taskNo 任务业务号（路径参数）
     * @param req    取消入参，非空
     * @return 取消后任务出参
     */
    @Operation(summary = "护理任务取消")
    @PostMapping("/api/v1/nursing/tasks/{taskNo}/cancel")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingTaskVO cancel(
            @PathVariable("taskNo") String taskNo, @Valid @RequestBody NursingTaskCancelRequest req) {
        return taskService.cancel(taskNo, req);
    }
}

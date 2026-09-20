package com.fuyun.outpatient.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.outpatient.dto.ExtraQuotaRequest;
import com.fuyun.outpatient.dto.ScheduleGenerateRequest;
import com.fuyun.outpatient.dto.SchedulePageQuery;
import com.fuyun.outpatient.dto.ScheduleTemplateSaveRequest;
import com.fuyun.outpatient.dto.StopScheduleRequest;
import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.service.IScheduleService;
import com.fuyun.outpatient.vo.NumberPoolVO;
import com.fuyun.outpatient.vo.ScheduleTemplateVO;
import com.fuyun.outpatient.vo.ScheduleVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 号源池域端点（/api/v1/outpatient 前缀，M03 Spec §7 资源复数小写连字符+动作子路径 POST 形态）：
 * 排班模板维护、T+N 放号生成、排班清单、停诊/恢复、可约号源查询与加号授权。写路径（模板维护/
 * 放号/停诊/恢复/加号=号源配置变更面）全量 @AuditLog(WRITE) 留痕；查询型端点不审计（M01 简报
 * §3.3 注解落点口径）。职责边界：仅 @Valid 校验+调用 service+编排响应，禁业务逻辑与事务
 * （宪法 B.1/A.1-8）。
 */
@Tag(name = "号源与排班")
@RestController
@RequestMapping("/api/v1/outpatient")
@RequiredArgsConstructor
public class ScheduleController {

    private final IScheduleService scheduleService;

    /**
     * 排班模板分页清单。
     *
     * @param page 页码（0 基），缺省 0
     * @param size 单页条数，缺省 20
     * @return 分页出参 {content,page,size,total}，非空
     */
    @Operation(summary = "排班模板分页清单")
    @GetMapping("/schedule-templates")
    public PageResult<ScheduleTemplateVO> listTemplates(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return scheduleService.listTemplates(page, size);
    }

    /**
     * 登记排班模板（号源配置变更，审计留痕）。
     *
     * @param request 模板保存请求，非空；格式约束由 JSR-303 校验
     * @return 登记后的模板出参（含回填 id），非空
     */
    @Operation(summary = "登记排班模板")
    @PostMapping("/schedule-templates")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ScheduleTemplateVO createTemplate(@Valid @RequestBody ScheduleTemplateSaveRequest request) {
        return scheduleService.saveTemplate(request);
    }

    /**
     * 更新排班模板（按 id 全量覆盖请求面字段，号源配置变更，审计留痕）。
     *
     * @param request 模板保存请求（id 必填），非空
     * @return 更新后的模板出参，非空
     */
    @Operation(summary = "更新排班模板")
    @PutMapping("/schedule-templates")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ScheduleTemplateVO updateTemplate(@Valid @RequestBody ScheduleTemplateSaveRequest request) {
        return scheduleService.saveTemplate(request);
    }

    /**
     * T+N 放号生成（号源配置变更，审计留痕）：按模板 week_pattern 位串×日期窗口批量展开排班
     * 与池行，uk 命中日期幂等跳过，池键预热。
     *
     * @param request 放号请求（endDate=窗口截止日，days=窗口天数），非空
     * @return 本次实际生成排班行数
     */
    @Operation(summary = "T+N 放号生成")
    @PostMapping("/schedules/generate")
    @AuditLog(actionType = AuditActionType.WRITE)
    public int generate(@Valid @RequestBody ScheduleGenerateRequest request) {
        return scheduleService.generate(request);
    }

    /**
     * 排班日历分页清单（deptCode/dateFrom/dateTo 可选过滤，sched_date+id 升序）。
     *
     * @param deptCode 开诊科室编码，可空
     * @param dateFrom 排班日期下界（含），可空
     * @param dateTo   排班日期上界（含），可空
     * @param page     页码（0 基），缺省 0
     * @param size     单页条数，缺省 20
     * @return 分页出参 {content,page,size,total}，非空
     */
    @Operation(summary = "排班日历分页清单")
    @GetMapping("/schedules")
    public PageResult<ScheduleVO> listSchedules(
            @RequestParam(required = false) String deptCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return scheduleService.listSchedules(new SchedulePageQuery(deptCode, dateFrom, dateTo, page, size));
    }

    /**
     * 停诊（审计留痕）：schedule CAS NORMAL→STOPPED+整池 STOPPED+发布 schedule.stopped
     * （已约患者改期/退费联动依据）。
     *
     * @param id      排班主键（路径参数）
     * @param request 停诊原因请求，非空
     */
    @Operation(summary = "停诊")
    @PostMapping("/schedules/{id}/stop")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void stop(@PathVariable("id") long id, @Valid @RequestBody StopScheduleRequest request) {
        scheduleService.stop(id, request.reason());
    }

    /**
     * 恢复停诊（审计留痕）：过期排班拒绝（OP-1004），整池迁回 ACTIVE。
     *
     * @param id 排班主键（路径参数）
     */
    @Operation(summary = "恢复停诊")
    @PostMapping("/schedules/{id}/resume")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void resume(@PathVariable("id") long id) {
        scheduleService.resume(id);
    }

    /**
     * 可约号源查询（供全渠道与 M18 复用）：仅 ACTIVE 且有余量行，slot_start 升序。
     *
     * @param deptCode 开诊科室编码，必填
     * @param date     排班日期，必填
     * @param apptType 号别过滤，可空（缺省=全部号别）
     * @return 可约池行出参集（含 remaining），非空；无可约号源返回空列表
     */
    @Operation(summary = "可约号源查询")
    @GetMapping("/number-pools/available")
    public List<NumberPoolVO> availablePools(
            @RequestParam String deptCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) ApptType apptType) {
        return scheduleService.availablePools(deptCode, date, apptType);
    }

    /**
     * 加号授权（审计留痕）：池行 total_quota 增量（1~50，越界 OP-1019）并同步刷新 Redis 快路径
     * 余量（INCRBY+续期 TTL）；加号占用计数走 extra_used（Task 5 挂号时按号段归入）。
     *
     * @param id      池行主键（路径参数）
     * @param request 加号数量请求，非空
     */
    @Operation(summary = "加号授权")
    @PostMapping("/number-pools/{id}/extra-quota")
    @AuditLog(actionType = AuditActionType.WRITE)
    public void extraQuota(@PathVariable("id") long id, @Valid @RequestBody ExtraQuotaRequest request) {
        scheduleService.extraQuota(id, request.count());
    }
}

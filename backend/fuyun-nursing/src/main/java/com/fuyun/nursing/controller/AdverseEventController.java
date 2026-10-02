package com.fuyun.nursing.controller;

import com.fuyun.common.web.PageResult;
import com.fuyun.nursing.dto.AdverseEventCloseRequest;
import com.fuyun.nursing.dto.AdverseEventHandleRequest;
import com.fuyun.nursing.dto.AdverseEventReportRequest;
import com.fuyun.nursing.dto.AdverseEventReturnRequest;
import com.fuyun.nursing.service.IAdverseEventService;
import com.fuyun.nursing.vo.AdverseEventStatsVO;
import com.fuyun.nursing.vo.AdverseEventVO;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理不良事件端点（/api/v1/nursing/adverse-events + /api/v1/nursing/stats/adverse-events）
 * ——FU-M05-09（05-nursing Spec）：上报（匿名通道+I/II 级 24 小时时限合规）、处置/关闭/
 * 退回状态机三路、分页查询与分类统计。非惩罚红线：出参零惩罚字段（仅流程改进面）。
 * controller 禁业务逻辑与事务（A.1-8）；写端点全挂 WRITE 审计（GC27——上报与处置族），
 * 查询/统计面不加审计读注记（W-47 统一收口患者查询 GET 审计面）。类级 @RequestMapping
 * 不承载（方法级全路径自文档，OrderExecutionController 同款）。
 */
@Tag(name = "M05 护理不良事件", description = "上报（匿名通道/24 小时时限）/处置/关闭（RCA）/退回/分类统计")
@RestController
@RequiredArgsConstructor
@Validated
public class AdverseEventController {

    private final IAdverseEventService adverseEventService;

    /**
     * 上报不良事件：I/II 级 report_deadline=occurredAt+24h 落库并上报即判定 deadline_met；
     * 落库后事务内发布 nursing.adverse-event.reported（id 83，M19 消费缺位登记）；
     * 匿名通道（isAnonymous=true）不落上报人。
     *
     * @param req 上报入参（category 八类/severityClass I~IV/severityGrade A~E/wardId/
     *            occurredAt/eventSummary 必填），非空
     * @return 上报后出参（非惩罚面）
     * @throws com.fuyun.common.exception.BizException NS-1019（400 词表外值）/ NS-1016
     *                 （409 occurredAt 超容差未来时刻）
     */
    @Operation(summary = "上报不良事件（匿名通道+I/II 级 24 小时时限落库+id 83 事件）", operationId = "reportAdverseEvent")
    @PostMapping("/api/v1/nursing/adverse-events")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdverseEventVO report(@Valid @RequestBody AdverseEventReportRequest req) {
        return adverseEventService.report(req);
    }

    /**
     * 不良事件分页查询：类别/病区/状态/发生日可选过滤（发生时点降序）；date 缺省不限时段
     * （在途处置队列跨日存续）。非惩罚红线：出参零惩罚字段。
     *
     * @param category 事件类别过滤（八类词表，可空），可空；来源：查询参数
     * @param wardId   病区过滤（可空），可空；来源：查询参数
     * @param status   处置状态过滤（REPORTED/HANDLING/CLOSED，可空），可空；来源：查询参数
     * @param date     发生日期过滤（北京钟面当日窗口，可空=不限时段），可空；来源：查询参数
     * @param page     页码（0 基），缺省 0
     * @param size     单页条数（1-200），缺省 20
     * @return 不良事件分页出参
     * @throws com.fuyun.common.exception.BizException NS-1019（400 category/status 词表外值）
     */
    @Operation(summary = "不良事件分页查询（非惩罚红线：出参零惩罚字段）", operationId = "listAdverseEvents")
    @GetMapping("/api/v1/nursing/adverse-events")
    public PageResult<AdverseEventVO> list(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "wardId", required = false) String wardId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(200) int size) {
        return adverseEventService.list(category, wardId, status, date, page, size);
    }

    /**
     * 受理处置（REPORTED→HANDLING）：落处置责任人与处置记录；I/II 级 REPORTED 态已超
     * 24h 处理时 deadline_met=false 留痕（不阻断——非惩罚原则）。
     *
     * @param no  不良事件号（路径参数 {no}），非空
     * @param req 处置入参（handlerId 必填/handlingNote 可空），非空
     * @return 处置后出参
     * @throws com.fuyun.common.exception.BizException NS-1025（404）/ NS-1026（409 非 REPORTED 态）
     */
    @Operation(summary = "受理处置（REPORTED→HANDLING；I/II 级超时留痕不阻断）", operationId = "handleAdverseEvent")
    @PostMapping("/api/v1/nursing/adverse-events/{no}/handle")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdverseEventVO handle(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody AdverseEventHandleRequest req) {
        return adverseEventService.handle(no, req);
    }

    /**
     * 关闭（HANDLING→CLOSED）：RCA 根因分析与整改措施随关闭落库（流程改进面）。
     *
     * @param no  不良事件号（路径参数 {no}），非空
     * @param req 关闭入参（closedBy 必填/rcaNote/correctiveAction 可空），非空
     * @return 关闭后出参
     * @throws com.fuyun.common.exception.BizException NS-1025（404）/ NS-1026（409 非 HANDLING 态）
     */
    @Operation(summary = "关闭（HANDLING→CLOSED；RCA 与整改措施随关闭落库）", operationId = "closeAdverseEvent")
    @PostMapping("/api/v1/nursing/adverse-events/{no}/close")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdverseEventVO close(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody AdverseEventCloseRequest req) {
        return adverseEventService.close(no, req);
    }

    /**
     * 处置退回（HANDLING→REPORTED，独立 return 端点承载）：退回原因覆写处置记录，
     * 行回 REPORTED 态可再处置。
     *
     * @param no  不良事件号（路径参数 {no}），非空
     * @param req 退回入参（reason/returnerId 必填），非空
     * @return 退回后出参
     * @throws com.fuyun.common.exception.BizException NS-1025（404）/ NS-1026（409 非 HANDLING 态）
     */
    @Operation(summary = "处置退回（HANDLING→REPORTED；退回原因留痕）", operationId = "returnAdverseEvent")
    @PostMapping("/api/v1/nursing/adverse-events/{no}/return")
    @AuditLog(actionType = AuditActionType.WRITE)
    public AdverseEventVO returnEvent(
            @PathVariable("no") @NotBlank String no, @Valid @RequestBody AdverseEventReturnRequest req) {
        return adverseEventService.returnEvent(no, req);
    }

    /**
     * 分类统计（统计日窗口按类别/病区/等级双维度/班次时段聚合计数+I/II 级时限合规面）：
     * 供 M19 护理质量指标消费（缺位注记）。非惩罚红线：纯计数聚合零个人面。
     *
     * @param category 类别前置过滤（可空），可空；来源：查询参数
     * @param wardId   病区前置过滤（可空），可空；来源：查询参数
     * @param date     统计日（北京钟面，可空=当日），可空；来源：查询参数
     * @return 统计聚合出参
     * @throws com.fuyun.common.exception.BizException NS-1019（400 category 词表外值）
     */
    @Operation(summary = "分类统计趋势（类别/病区/等级/时段聚合计数，M19 消费缺位注记）", operationId = "statsAdverseEvents")
    @GetMapping("/api/v1/nursing/stats/adverse-events")
    public AdverseEventStatsVO stats(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "wardId", required = false) String wardId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return adverseEventService.stats(category, wardId, date);
    }
}

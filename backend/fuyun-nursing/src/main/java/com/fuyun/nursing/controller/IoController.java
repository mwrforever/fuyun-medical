package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.IoRecordCreateRequest;
import com.fuyun.nursing.dto.IoSummaryCreateRequest;
import com.fuyun.nursing.service.IIoRecordService;
import com.fuyun.nursing.vo.IoRecordVO;
import com.fuyun.nursing.vo.IoSummaryVO;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出入量端点（/api/v1/nursing/io-records、/api/v1/nursing/io-summaries，Spec :159 端点面）。
 * 明细录入与小结生成为写面（审计留痕）；小结清单为小结回读面（Spec :159 节选未列，随冻结
 * 服务面 summaries 补设）。体温单红双线标识由前端按 DAILY_VALUE 条目渲染，本端点不承载绘制。
 * 类级 @RequestMapping 不承载（WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "出入量")
@RestController
@RequiredArgsConstructor
public class IoController {

    private final IIoRecordService ioRecordService;

    /**
     * 出入量明细录入（手工/PDA；数量词表与正数校验 NS-1019，在区校验 NS-1004）。
     *
     * @param req 录入入参，非空
     * @return 明细行出参
     */
    @Operation(summary = "出入量明细录入")
    @PostMapping("/api/v1/nursing/io-records")
    @AuditLog(actionType = AuditActionType.WRITE)
    public IoRecordVO create(@Valid @RequestBody IoRecordCreateRequest req) {
        return ioRecordService.create(req);
    }

    /**
     * 按住院就诊号列出入量明细（date 非空时仅取该日，发生时间升序）。
     *
     * @param visitId 住院就诊号，必填
     * @param date    发生日（yyyy-MM-dd），可空
     * @return 明细出参清单（发生时间升序）
     */
    @Operation(summary = "出入量明细清单")
    @GetMapping("/api/v1/nursing/io-records")
    public List<IoRecordVO> listByVisit(
            @RequestParam("visitId") String visitId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return ioRecordService.listByVisit(visitId, date);
    }

    /**
     * 出入量小结生成（班次小结/24h 总结；同周期幂等返回既有行；随链写体温单 DAILY_VALUE 条目）。
     *
     * @param req 小结入参，非空
     * @return 小结行出参
     */
    @Operation(summary = "出入量小结生成（班次/24h）")
    @PostMapping("/api/v1/nursing/io-summaries")
    @AuditLog(actionType = AuditActionType.WRITE)
    public IoSummaryVO summarize(@Valid @RequestBody IoSummaryCreateRequest req) {
        return ioRecordService.summarize(req);
    }

    /**
     * 按住院就诊号列出入量小结（date 非空时仅取 period_start 落在该日的小结，周期起升序）。
     *
     * @param visitId 住院就诊号，必填
     * @param date    统计日（yyyy-MM-dd），可空
     * @return 小结出参清单（周期起升序）
     */
    @Operation(summary = "出入量小结清单")
    @GetMapping("/api/v1/nursing/io-summaries")
    public List<IoSummaryVO> summaries(
            @RequestParam("visitId") String visitId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return ioRecordService.summaries(visitId, date);
    }
}

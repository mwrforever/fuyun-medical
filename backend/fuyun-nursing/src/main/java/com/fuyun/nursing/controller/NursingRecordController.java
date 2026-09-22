package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.NursingRecordCreateRequest;
import com.fuyun.nursing.dto.NursingRecordReviseRequest;
import com.fuyun.nursing.service.INursingRecordService;
import com.fuyun.nursing.vo.NursingRecordVO;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理记录单端点（/api/v1/nursing/nursing-records）。提交/修订为动作子路径 POST
 * （GC13）；提交后正文锁定（NS-1007），修订产生留痕新行（GC25 原值可见）——不提供任何
 * PUT/PATCH 正文变更端点（状态机唯一写入口为 create/submit/revise）。
 * 类级 @RequestMapping 不承载（WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "护理记录单")
@RestController
@RequiredArgsConstructor
public class NursingRecordController {

    private final INursingRecordService nursingRecordService;

    /**
     * 创建护理记录（草稿）。
     *
     * @param req 创建入参，非空
     * @return 记录行出参
     */
    @Operation(summary = "创建护理记录（草稿）")
    @PostMapping("/api/v1/nursing/nursing-records")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingRecordVO create(@Valid @RequestBody NursingRecordCreateRequest req) {
        return nursingRecordService.create(req);
    }

    /**
     * 按住院就诊号列护理记录（date 非空时仅取该日）。
     *
     * @param visitId 住院就诊号，必填
     * @param date    记录日（yyyy-MM-dd），可空
     * @return 记录出参清单（记录时间升序）
     */
    @Operation(summary = "护理记录清单")
    @GetMapping("/api/v1/nursing/nursing-records")
    public List<NursingRecordVO> listByVisit(
            @RequestParam("visitId") String visitId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return nursingRecordService.listByVisit(visitId, date);
    }

    /**
     * 按记录号回读单条护理记录。
     *
     * @param recordNo 护理记录号（路径参数）
     * @return 记录行出参
     */
    @Operation(summary = "护理记录详情")
    @GetMapping("/api/v1/nursing/nursing-records/{recordNo}")
    public NursingRecordVO get(@PathVariable("recordNo") String recordNo) {
        return nursingRecordService.get(recordNo);
    }

    /**
     * 提交锁定（DRAFT→SUBMITTED，签名留痕；提交后正文锁定）。
     *
     * @param recordNo 护理记录号（路径参数）
     * @return 提交后记录行出参
     */
    @Operation(summary = "提交锁定护理记录")
    @PostMapping("/api/v1/nursing/nursing-records/{recordNo}/submit")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingRecordVO submit(@PathVariable("recordNo") String recordNo) {
        return nursingRecordService.submit(recordNo);
    }

    /**
     * 修订留痕（新行 REVISED + revised_from 链，原行保留原值可见）。
     *
     * @param recordNo 被修订的原记录号（路径参数）
     * @param req      修订入参，非空
     * @return 修订件出参
     */
    @Operation(summary = "修订护理记录（留痕）")
    @PostMapping("/api/v1/nursing/nursing-records/{recordNo}/revise")
    @AuditLog(actionType = AuditActionType.WRITE)
    public NursingRecordVO revise(
            @PathVariable("recordNo") String recordNo, @Valid @RequestBody NursingRecordReviseRequest req) {
        return nursingRecordService.revise(recordNo, req);
    }
}

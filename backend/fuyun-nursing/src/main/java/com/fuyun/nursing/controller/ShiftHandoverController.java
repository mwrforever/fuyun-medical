package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.HandoverCompleteRequest;
import com.fuyun.nursing.dto.HandoverGenerateRequest;
import com.fuyun.nursing.service.IShiftHandoverService;
import com.fuyun.nursing.vo.ShiftHandoverVO;
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
 * 交接班端点（/api/v1/nursing/handovers，GC13 资源复数 + 动作子路径 POST；Spec :168 三端点）：
 * 生成（SBAR 自动汇总初稿）/ 完成（双签确认 + nursing.shift.completed）/ 病区清单（按日检索）。
 * 分步双人签署（DRAFT→SIGNING 独立迁移入口）归 P2 不设端点。类级 @RequestMapping 不承载
 * （NursingTaskController 同款：端点集合可结构断言）。
 */
@Tag(name = "交接班")
@RestController
@RequiredArgsConstructor
public class ShiftHandoverController {

    private final IShiftHandoverService handoverService;

    /**
     * 交接班单生成（系统按本班业务数据自动汇总患者摘要/SBAR 初稿/待续事项，
     * 生成即盖章交班签名，落 DRAFT 态）。
     *
     * @param req 生成入参，非空
     * @return 交接班出参（DRAFT 态）
     */
    @Operation(summary = "交接班单生成（SBAR 自动汇总初稿）")
    @PostMapping("/api/v1/nursing/handovers/generate")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ShiftHandoverVO generate(@Valid @RequestBody HandoverGenerateRequest req) {
        return handoverService.generate(req);
    }

    /**
     * 交接班完成（双签确认：DRAFT/SIGNING → COMPLETED，接班签名盖章并发布完成事件；
     * SBAR 四段空则保留汇总初稿）。
     *
     * @param handoverNo 交接班单业务号（路径参数）
     * @param req        完成入参（接班护士必填），非空
     * @return 完成后交接班出参
     */
    @Operation(summary = "交接班完成（双签确认）")
    @PostMapping("/api/v1/nursing/handovers/{handoverNo}/complete")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ShiftHandoverVO complete(
            @PathVariable("handoverNo") String handoverNo, @Valid @RequestBody HandoverCompleteRequest req) {
        return handoverService.complete(handoverNo, req);
    }

    /**
     * 病区交接班清单（按日检索，班次升序；date 缺省当日——列表按日检索语义）。
     *
     * @param wardId 病区编码，必填
     * @param date   交接班日期（ISO yyyy-MM-dd，可空，缺省当日）
     * @return 交接班出参清单（班次升序）
     */
    @Operation(summary = "病区交接班清单（按日检索）")
    @GetMapping("/api/v1/nursing/handovers")
    public List<ShiftHandoverVO> list(
            @RequestParam("wardId") String wardId,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        return handoverService.listByWard(wardId, date == null ? LocalDate.now() : date);
    }
}

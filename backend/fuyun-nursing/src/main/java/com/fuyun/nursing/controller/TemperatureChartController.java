package com.fuyun.nursing.controller;

import com.fuyun.nursing.dto.ChartQueryRequest;
import com.fuyun.nursing.dto.SpecialEventRequest;
import com.fuyun.nursing.service.ITemperatureChartService;
import com.fuyun.nursing.vo.ChartEntryVO;
import com.fuyun.nursing.vo.TemperatureChartVO;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 体温单端点（/api/v1/nursing/temperature-charts）。月页查询为只读面；特殊事件录入为
 * 月页下子资源 POST（资源复数 + 小写连字符，GC13）。体征条目与日行值条目无公开端点
 * ——由体征域（Task 5）与出入量域（Task 6）服务面写入，禁端点直写绕过归集链。
 * 类级 @RequestMapping 不承载（WardController 同款：端点集合可结构断言）。
 */
@Tag(name = "体温单")
@RestController
@RequiredArgsConstructor
public class TemperatureChartController {

    private final ITemperatureChartService temperatureChartService;

    /**
     * 体温单月页查询（三类条目三段分组，各段按条目时点升序）。
     *
     * @param query 查询入参（visitId + month），非空
     * @return 月页出参
     */
    @Operation(summary = "体温单月页查询")
    @GetMapping("/api/v1/nursing/temperature-charts")
    public TemperatureChartVO getChart(@Valid ChartQueryRequest query) {
        return temperatureChartService.getChart(query.visitId(), query.month());
    }

    /**
     * 特殊事件录入（SPECIAL_EVENT 条目）。
     *
     * @param visitId 住院就诊号（路径参数）
     * @param req     事件入参，非空
     * @return 事件条目出参
     */
    @Operation(summary = "体温单特殊事件录入")
    @PostMapping("/api/v1/nursing/temperature-charts/{visitId}/special-events")
    @AuditLog(actionType = AuditActionType.WRITE)
    public ChartEntryVO addSpecialEvent(
            @PathVariable("visitId") String visitId, @Valid @RequestBody SpecialEventRequest req) {
        return temperatureChartService.addSpecialEvent(visitId, req);
    }
}

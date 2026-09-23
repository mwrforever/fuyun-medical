package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 体温单查询入参（GET /api/v1/nursing/temperature-charts，query 绑定）。month 形如 2026-09
 * （yyyy-MM），服务端再经 YearMonth.parse 权威解析（格式违例 NS-1019，禁裸 parse）。
 *
 * @param visitId 住院就诊号（I 型 14 位），必填；来源：操作者工作站当前患者
 * @param month   体温单月页（yyyy-MM），必填；来源：操作者选择的月份
 */
public record ChartQueryRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,

        @NotBlank(message = "month 不能为空") @Pattern(regexp = "\\d{4}-\\d{2}", message = "month 格式必须为 yyyy-MM")
        String month) {}

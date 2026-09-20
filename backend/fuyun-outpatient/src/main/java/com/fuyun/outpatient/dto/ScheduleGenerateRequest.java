package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 放号生成请求（POST /api/v1/outpatient/schedules/generate，M03 FU-M03-01 T+N 放号规则）：
 * 按模板 week_pattern 位串×日期区间 [endDate-(days-1), endDate] 批量展开排班日历与号源池行，
 * uk_schedule 命中日期幂等跳过。
 *
 * @param endDate 放号窗口截止日，非空；调用方约定不早于当日（面向未来放号，过期窗口将因池键
 *                负 TTL 显式失败）；来源：放号任务/管理端表单
 * @param days    放号窗口天数（T+N 的 N），1~31；来源：放号任务/管理端表单
 */
public record ScheduleGenerateRequest(
        @NotNull(message = "endDate 不得为空") LocalDate endDate,

        @NotNull(message = "days 不得为空")
        @Min(value = 1, message = "days 最少 1 天")
        @Max(value = 31, message = "days 单批最多 31 天")
        Integer days) {}

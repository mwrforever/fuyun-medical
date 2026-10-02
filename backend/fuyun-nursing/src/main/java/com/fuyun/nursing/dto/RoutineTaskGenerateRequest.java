package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 常规模板批量生成入参（POST /api/v1/nursing/tasks/generate-routine，P2 PR-3 Task 9）：
 * 按 ward_config.routine_task_templates 模板对在区患者投影批量生成当日任务。
 *
 * @param wardId 病区编码，必填；来源：任务工作台/病区管理触发（生成作用域）
 * @param date   生成日期（ISO yyyy-MM-dd），可空（空=北京钟面当日——当日余下时段按模板频次切片）
 */
public record RoutineTaskGenerateRequest(
        @NotBlank(message = "病区编码必填（wardId）") String wardId, LocalDate date) {}

package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 停诊请求（POST /api/v1/outpatient/schedules/{id}/stop，M03 FU-M03-01）：整池作废+已约患者
 * 联动依据，原因为 schedule.stopped 事件与排班行 stop_reason 的留痕载体。
 *
 * @param reason 停诊原因，非空（审计与事件载荷必填）；来源：管理端停诊弹窗
 */
public record StopScheduleRequest(
        @NotBlank(message = "reason 不得为空") String reason) {}

package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 体温单特殊事件录入入参（POST /api/v1/nursing/temperature-charts/{visitId}/special-events）。
 * 事件时点不设入参组件——文书业务时间一律服务器时间（GC25 护理文书红线，服务端取录入时点）。
 *
 * @param eventType 特殊事件类型（SpecialEventType code，非法值 NS-1019），必填；来源：操作者选择
 * @param remark    备注（如物理降温前体温），可空；来源：操作者录入
 */
public record SpecialEventRequest(
        @NotBlank(message = "eventType 不能为空") String eventType, String remark) {}

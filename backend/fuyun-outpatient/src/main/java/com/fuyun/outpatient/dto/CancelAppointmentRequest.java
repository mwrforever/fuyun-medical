package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 退号请求（POST /appointments/{no}/cancel 与 portal 匿名退号共用，Task 6）：原因为必填留痕要素
 * （审计抽查与 appointment.cancelled 事件 reason 组件同源，操作者录入）。
 *
 * @param reason 退号原因，非空白；来源：操作者/患者录入（事件载荷脱敏红线由发布侧承载）
 */
public record CancelAppointmentRequest(@NotBlank String reason) {}

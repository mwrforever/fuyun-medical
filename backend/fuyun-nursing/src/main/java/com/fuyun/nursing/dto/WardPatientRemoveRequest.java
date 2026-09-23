package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 移出病区一览入参（POST /api/v1/ward-patients/{visitId}/remove，GC38 四护栏载体）。
 *
 * <p>语义边界：本动作仅将本地视图行置 REMOVED（移出病区一览），<b>无任何出院/转科语义</b>——
 * P1 演示中患者离开病区只能表达为「移出病区一览」（残余限制，2026-09-22 用户明示接受，
 * 随 P2 事件链上线与过渡通道退役而消失）。reason 仅入审计/日志留痕，不落库、不新增列。
 *
 * @param reason 移出原因（操作留痕，仅入审计），必填；来源：操作者录入
 */
public record WardPatientRemoveRequest(
        @NotBlank(message = "reason 不能为空") String reason) {}

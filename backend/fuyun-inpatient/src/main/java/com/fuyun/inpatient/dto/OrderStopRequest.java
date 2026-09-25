package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 医嘱停嘱入参（POST /api/v1/inpatient/orders/{no}/stop）：医生停嘱理由（临床停嘱依据——
 * 病情好转/换方案等）；停嘱状态面（AUDITED/TRANSFERRED/EXECUTING→STOPPED）归状态机裁决，
 * 本入参仅承载理由。长度对齐 V904 stop_reason 列宽（255），超限 Web 层 4xx 拒绝（禁落库 500）。
 *
 * @param reason 停嘱原因（临床停嘱依据），必填 ≤255 字符；来源：医生站停嘱录入
 */
public record OrderStopRequest(
        @NotBlank(message = "reason 不能为空") @Size(max = 255, message = "reason 长度须 ≤255")
        String reason) {}

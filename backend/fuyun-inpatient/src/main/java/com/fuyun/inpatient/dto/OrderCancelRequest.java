package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 医嘱作废入参（POST /api/v1/inpatient/orders/{no}/cancel）：作废理由（开错/重复开立等）；
 * 作废仅限未产生执行的医嘱（AUDITED/TRANSFERRED 态，EXECUTING 起状态机拒 IP-1010），
 * 执行单撤销归 M05 消费 cancelled 事件。长度对齐事件载荷与留痕列宽（255），超限 4xx。
 *
 * @param reason 作废原因（临床作废依据），必填 ≤255 字符；来源：医生站作废录入
 */
public record OrderCancelRequest(
        @NotBlank(message = "reason 不能为空") @Size(max = 255, message = "reason 长度须 ≤255")
        String reason) {}

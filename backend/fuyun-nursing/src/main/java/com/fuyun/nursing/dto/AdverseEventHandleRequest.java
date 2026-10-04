package com.fuyun.nursing.dto;

import jakarta.validation.constraints.Size;

/**
 * 不良事件受理处置入参（POST /api/v1/nursing/adverse-events/{no}/handle）：
 * REPORTED→HANDLING 迁移载体；I/II 级 REPORTED 态已超 24h 处置时 deadline_met=false
 * 留痕（不阻断处理——非惩罚原则）。处置责任人=登录令牌身份（W-72，2026-10-03 裁决——
 * handlerId 兼容保留忽略）。
 *
 * @param handlerId    处置责任人员工 ID（兼容保留——服务端一律以令牌身份落值，本字段不再消费），
 *                     可空；来源：处置表单
 * @param handlingNote 处置记录（≤1000 可空——缺省保留上报时初步处置记录），可空；来源：处置表单
 */
public record AdverseEventHandleRequest(
        Long handlerId,

        @Size(max = 1000, message = "处置记录超长（≤1000）") String handlingNote) {}

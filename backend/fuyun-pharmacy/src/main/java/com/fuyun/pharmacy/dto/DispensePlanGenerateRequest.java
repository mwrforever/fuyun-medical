package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 住院摆药计划生成入参（POST /api/v1/pharmacy/dispense-plans/generate，P2 PR-3 Task 8）。
 * wardId 为显式入参的裁决依据：pharmacy 域内无住院病区归属解析面——order_medication 快照与
 * inpatient.order.created 载荷均不携带 wardId、跨模块读 nursing/inpatient 表禁止、零迁移授权
 * 无从扩列，故目标病区由发起端（药师摆药工作台按病区发起/IT 夹具）显式声明（偏离 brief
 * 「body 仅 m04OrderNo」最小扩展，报告 concerns 申报）。
 *
 * @param m04OrderNo 住院医嘱号（M04 medical_order.order_no），必填 ≤32；来源：摆药工作台医嘱清单
 * @param wardId     目标病区编码，必填 ≤64（全仓 ward_id 64 同宽口径）；来源：摆药工作台病区选择
 */
public record DispensePlanGenerateRequest(
        @NotBlank @Size(max = 32) String m04OrderNo,
        @NotBlank @Size(max = 64) String wardId) {}

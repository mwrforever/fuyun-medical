package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 诊毕请求（POST /api/v1/outpatient/visits/{visitId}/finish，M03 Spec :152）：离院去向词表校验
 * （outpatient.disposition 八码集=V705 item_code 全集，词表外 OP-1018）；在途单据（CREATED/
 * PENDING_FEE 申请单）存在时须显式确认方可诊毕（OP-1016）。M09 门诊文书完整性校验为参数化提醒、
 * 不拦截诊毕（not-in-scope，随 M09 病案域接入注记）。
 *
 * @param disposition     离院去向词表值（V705 outpatient.disposition item_code 全集），非空白；
 *                        来源：医生站诊毕弹窗
 * @param explicitConfirm 在途单据显式确认（true=明知有在途单据仍诊毕，如患者拒绝缴费离院），可空
 *                        （null 视为 false）；来源：医生站确认勾选
 */
public record FinishVisitRequest(
        @NotBlank(message = "disposition 不得为空白") String disposition, Boolean explicitConfirm) {}

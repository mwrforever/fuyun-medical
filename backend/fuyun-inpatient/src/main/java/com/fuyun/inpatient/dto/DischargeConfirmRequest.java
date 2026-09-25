package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 离院确认入参（POST /discharge-requests/{no}/confirm）——人工确认离院（GC19 双条件外的
 * 人工面）与随访计划参数承载：随访时距（出院后 N 日）、随访方式与内容摘要，三者全部可选
 * （缺省 14 日/电话/「出院随访」——缺省时距取 fuyun.inpatient.follow-up-interval-days
 * 参数，Task 10 回接参数化；出院医嘱三要素之随访必生成，参数化仅为差异化场景）。
 * 确认人与时点取操作者上下文与服务器时间（禁前端传人/传时点）。
 *
 * @param followUpDays    随访时距（天，plan_date=出院日后 N 日），可空缺省取域参数（默认 14）；
 *                        来源：医生站出院确认单（出院医嘱随访要素）
 * @param followUpWay     随访方式（PHONE 电话/WECHAT 公众号/REVISIT 复诊），可空缺省 PHONE；
 *                        来源：医生站出院确认单
 * @param followUpSummary 随访内容摘要（复诊提示/用药指导/康复注意等），可空缺省「出院随访」；
 *                        来源：医生站出院确认单
 */
public record DischargeConfirmRequest(
        @Min(1) @Max(365) Integer followUpDays,
        @Pattern(regexp = "PHONE|WECHAT|REVISIT") String followUpWay,
        @Size(max = 255) String followUpSummary) {}

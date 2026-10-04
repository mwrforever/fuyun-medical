package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 不良事件上报入参（POST /api/v1/nursing/adverse-events）：匿名通道（isAnonymous=true
 * 时 reporter_id 落 NULL——非惩罚文化红线）与 I/II 级 24 小时强制上报时限（deadline=
 * occurredAt+24h 落库，上报即判定 deadline_met）的上报表单载体。
 *
 * @param category      事件类别（八类词表必填：MEDICATION_ERROR/FALL/PRESSURE_ULCER/
 *                      TUBE_SLIP/BLOOD_TRANFUSION/DEVICE/FACILITY/OTHER，服务侧 fromCode
 *                      校验），非空；来源：上报表单
 * @param severityClass 严重度分级（I/II/III/IV 必填，服务侧 fromCode 校验），非空；来源：上报表单
 * @param severityGrade 严重度等级（A~E 必填，服务侧 fromCode 校验），非空；来源：上报表单
 * @param wardId        发生病区编码（≤64 必填），非空；来源：上报表单
 * @param visitId       住院就诊号（≤14 可空——设施类事件可无就诊主体），可空；来源：上报表单
 * @param patientId     患者主索引（可空，同上），可空；来源：上报表单
 * @param occurredAt    事件发生时点（必填据实填报；不得晚于当前时间+容差——禁未来时刻倒灌），
 *                      非空；来源：上报表单
 * @param eventSummary  事件经过（≤2000 必填），非空；来源：上报表单
 * @param handlingNote  处置情况（≤1000 可空——上报时初步处置记录，缺省落空串），可空；
 *                      来源：上报表单
 * @param reporterId    上报人员工 ID（兼容保留忽略——归属由 isAnonymous+令牌承载，W-72，
 *                      2026-10-03 裁决；本字段不再消费），可空；来源：上报表单
 * @param isAnonymous   匿名上报标识（显式 true 走匿名通道——reporter_id 落 NULL 不取令牌；
 *                      缺省 false 默认令牌实名，与前端默认一致，「reporterId==null 即匿名」
 *                      归一随 W-72 作废），可空；来源：上报表单
 */
public record AdverseEventReportRequest(
        @NotBlank(message = "事件类别必填（category）") String category,

        @NotBlank(message = "严重度分级必填（severityClass）") String severityClass,

        @NotBlank(message = "严重度等级必填（severityGrade）") String severityGrade,

        @NotBlank(message = "病区编码必填（wardId）") @Size(max = 64, message = "病区编码超长（≤64）")
        String wardId,

        @Size(max = 14, message = "就诊号超长（≤14）") String visitId,

        Long patientId,

        @NotNull(message = "事件发生时点必填（occurredAt）") OffsetDateTime occurredAt,

        @NotBlank(message = "事件经过必填（eventSummary）") @Size(max = 2000, message = "事件经过超长（≤2000）")
        String eventSummary,

        @Size(max = 1000, message = "处置情况超长（≤1000）") String handlingNote,

        Long reporterId,

        Boolean isAnonymous) {}

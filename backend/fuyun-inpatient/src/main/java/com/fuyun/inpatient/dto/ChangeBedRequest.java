package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 同病区转床入参（POST /api/v1/inpatient/visits/{visitId}/change-bed）——转科编排的轻量
 * 路径（04-inpatient Spec §3.5 裁决：无医嘱停嘱步骤，仅床位切换与事件）：转出床消毒流转 →
 * 目标床占床（bed_assign assign_type=BED_CHANGE）→ visit current_bed 原子更新 → 发布
 * inpatient.visit.transferred（前后病区相同）。目标床位须与当前床位同病区且不同床。
 *
 * @param toBedId 目标床位 id，必填；来源：护士站床位调整
 */
public record ChangeBedRequest(
        @NotNull(message = "toBedId 不能为空") Long toBedId) {}

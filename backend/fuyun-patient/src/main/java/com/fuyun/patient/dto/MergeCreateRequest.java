package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发起合并请求（POST /merges，FU-M02-03；双人角色第一步——发起即经办）。
 *
 * @param survivorPatientId   主档 id，非空；来源：工作台双档对照
 * @param mergedPatientId     从档 id，非空（与主档不得相同，服务端校验）
 * @param mergeReason         合并原因，非空（审计必填）
 * @param possibleDuplicateId 关联疑似重复行（发起来源可空）；来源：工作台
 */
public record MergeCreateRequest(
        @NotNull Long survivorPatientId,
        @NotNull Long mergedPatientId,
        @NotBlank String mergeReason,
        Long possibleDuplicateId) {}

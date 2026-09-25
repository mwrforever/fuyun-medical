package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 床位占床入参（POST /api/v1/inpatient/beds/{id}/assign）：FREE/RESERVED→OCCUPIED 直接
 * 分配入口（入科快速通道，床位流转权威动作；占用主体经就诊号解析）。开 bed_assign 占用
 * 流水（assign_type=ADMISSION）并广播 inpatient.bed.changed；入科确认联动路径
 * （admit-ward）走 BedService.occupyForAdmission 同源 CAS。
 *
 * @param visitId 占用主体住院就诊号（I 型 14 位，入科确认已签发；仅已登记待入科/在院态可分配），必填；来源：护士站分配床位
 */
public record BedAssignRequest(
        @NotBlank(message = "visitId 不能为空") @Pattern(regexp = "I\\d{13}", message = "visitId 须为 I 型 14 位住院就诊号")
        String visitId) {}

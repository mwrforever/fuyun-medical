package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 预约入院/预住院入参（POST /api/v1/inpatient/admissions/{no}/schedule）：WAITING→SCHEDULED，
 * 记录目标床位与预约日期。目标床位 RESERVED 预占联动（BedService.reserveForAdmission）归
 * Task 4 随 V903 bed 落地后补齐——本请求先承载目标面数据。
 *
 * @param targetWardId 目标病区编码，可空（全院一张床跨病区签床场景允许病区缺席）；来源：签床调度
 * @param targetBedId  目标床位 id，可空（预住院模式允许无床虚拟登记；bed 表归 V903/Task 4）；来源：签床调度
 * @param expectDate   预约入院日期（队列排序第二键=预约时段），必填；来源：登记台与患者约定
 */
public record AdmissionScheduleRequest(
        String targetWardId,
        Long targetBedId,
        @NotNull(message = "expectDate 不能为空") LocalDate expectDate) {}

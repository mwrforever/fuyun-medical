package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 入科确认入参（POST /api/v1/inpatient/visits/{visitId}/admit-ward）：visit REGISTERED→ADMITTED，
 * 登记床位/病区/护理级别；服务层同事务联动床位
 * RESERVED→OCCUPIED 流转与 bed_assign 占用流水开账（BedService.occupyForAdmission——占床失败整体入科事务回滚）。
 *
 * @param deptId            入科科室编码（M01 组织机构 code，病区归属科室缺席时容许），可空；来源：护士站入科单
 * @param wardId            入科病区编码，必填；来源：护士站入科单
 * @param bedId             入科床位 id，必填；来源：护士站分配床位
 * @param attendingDoctorId 主治医生（M01 用户标识，医生站后补维护容许），可空；来源：护士站指定
 * @param nursingLevel      护理级别 code（SPECIAL 特级/CRITICAL 病重/NORMAL 普通；权威在 M04），必填；来源：护士评估
 */
public record WardAdmitRequest(
        String deptId,
        @NotBlank(message = "wardId 不能为空") String wardId,
        @NotNull(message = "bedId 不能为空") Long bedId,

        @NotBlank(message = "nursingLevel 不能为空")
        @Pattern(regexp = "SPECIAL|CRITICAL|NORMAL", message = "nursingLevel 词表外")
        String nursingLevel,

        String attendingDoctorId) {}

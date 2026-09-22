package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 责任护士分配入参（POST /api/v1/nursing/assignments，FU-M05-01）。
 *
 * <p>类型一致性由服务端显式校验：assignmentType=PRIMARY 时 patientId 必填且 bedNo 必须为空、
 * assignmentType=BED 时 bedNo 必填且 patientId 必须为空，不一致 NS-1019 拒收；重复分配
 * （同病区同床位/患者 × 同班次同生效日 ACTIVE）NS-1002 拒收。
 *
 * @param wardId         病区编码，必填；来源：操作者当前工作站病区
 * @param nurseId        护士标识（M01 用户标识），必填；来源：操作者选择
 * @param assignmentType 分配类型（AssignmentType code：PRIMARY/BED），必填；来源：操作者选择
 * @param shiftCode      班次 code（取 nursing_ward_config.shift_definitions），必填；来源：操作者选择
 * @param bedNo          管床床位号（BED 型必填、PRIMARY 型禁填），可空；来源：操作者选择
 * @param patientId      责任患者（PRIMARY 型必填、BED 型禁填），可空；来源：操作者选择
 * @param validFrom      生效日期，可空缺省当日；来源：操作者选择
 * @param validTo        失效日期（空=长期），可空；来源：操作者选择
 */
public record NurseAssignmentRequest(
        @NotBlank(message = "wardId 不能为空") String wardId,
        @NotBlank(message = "nurseId 不能为空") String nurseId,
        @NotBlank(message = "assignmentType 不能为空") String assignmentType,
        @NotBlank(message = "shiftCode 不能为空") String shiftCode,
        String bedNo,
        Long patientId,
        LocalDate validFrom,
        LocalDate validTo) {}

package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.NurseAssignment;
import java.time.LocalDate;

/**
 * 责任护士分配出参（分配/撤销/当班清单共用；交接班 Task 9 消费同一冻结面）。
 *
 * @param id             分配 id（DELETE /assignments/{id} 定位锚）
 * @param wardId         病区编码
 * @param nurseId        护士标识（M01 用户标识）
 * @param assignmentType 分配类型（AssignmentType code：PRIMARY/BED）
 * @param shiftCode      班次 code
 * @param bedNo          管床床位号（BED 型非空）
 * @param patientId      责任患者（PRIMARY 型非空）
 * @param validFrom      生效日期
 * @param validTo        失效日期（空=长期）
 * @param status         分配状态（ACTIVE/CANCELLED）
 */
public record NurseAssignmentVO(
        Long id,
        String wardId,
        String nurseId,
        String assignmentType,
        String shiftCode,
        String bedNo,
        Long patientId,
        LocalDate validFrom,
        LocalDate validTo,
        String status) {

    /**
     * 实体→出参静态工厂（手写映射）。
     *
     * @param entity 分配行，非空
     * @return 分配出参，非空
     */
    public static NurseAssignmentVO from(NurseAssignment entity) {
        return new NurseAssignmentVO(
                entity.getId(),
                entity.getWardId(),
                entity.getNurseId(),
                entity.getAssignmentType(),
                entity.getShiftCode(),
                entity.getBedNo(),
                entity.getPatientId(),
                entity.getValidFrom(),
                entity.getValidTo(),
                entity.getStatus());
    }
}

package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.Prescription;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 处方出参（from(entity, items) 手写映射——关键业务字段禁 MapStruct，A.7-4；Long 经 Jackson→string）。
 * 组件清单为 Task 4–11 依赖的冻结面（brief Interfaces 节）：枚举 code 直出、诊断引用逗号分隔还原。
 */
public record PrescriptionVO(
        Long id,
        String rxNo,
        String rxType,
        Long patientId,
        String visitId,
        String doctor,
        String deptCode,
        List<String> diagnosisCodes,
        String rxCategory,
        boolean skinTestRequired,
        String reviewLevel,
        String status,
        String cancelReason,
        List<PrescriptionItemVO> items,
        OffsetDateTime createdAt) {

    /**
     * 实体→出参静态工厂（diagnosisCodes 逗号分隔文本还原为集，items 随行装载）。
     *
     * @param entity 处方行，非空
     * @param items  明细出参清单（调用方按处方 id 装载），非空
     * @return 出参，非空
     */
    public static PrescriptionVO from(Prescription entity, List<PrescriptionItemVO> items) {
        return new PrescriptionVO(
                entity.getId(),
                entity.getRxNo(),
                entity.getRxType(),
                entity.getPatientId(),
                entity.getVisitId(),
                entity.getDoctor(),
                entity.getDeptCode(),
                entity.getDiagnosisCodes() == null || entity.getDiagnosisCodes().isBlank()
                        ? List.of()
                        : List.of(entity.getDiagnosisCodes().split(",")),
                entity.getRxCategory(),
                Boolean.TRUE.equals(entity.getSkinTestRequired()),
                entity.getReviewLevel(),
                entity.getStatus(),
                entity.getCancelReason(),
                items,
                entity.getCreatedAt());
    }
}

package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.Admission;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 住院证出参（候床队列行/建单/预约/作废确认共用；实体禁直出——出网边界唯一出口）。
 *
 * @param admissionNo      住院证号（AD+yyyyMMdd+5 位流水，string 承载）
 * @param patientId        患者主索引（归一主档口径）
 * @param sourceType       来源 code（SourceType）
 * @param sourceVisitId    门诊 visit_id 引用（O 型 14 位；非转诊来源为 null）
 * @param targetDeptId     目标科室编码
 * @param targetWardId     目标病区编码（schedule 预约写入）
 * @param targetBedId      目标床位 id（schedule 预约写入；bed 表见 V903）
 * @param admissionType    入院类型 code（AdmissionType；EMERGENCY 为队列第一优先键）
 * @param expectDate       预约入院日期（队列排序第二键）
 * @param diagnosisSummary 入院诊断摘要（敏感文本，仅回显不外发事件）
 * @param issuedDoctorId   开证医生
 * @param status           状态 code（AdmissionStatus：WAITING/SCHEDULED/COMPLETED/CANCELLED）
 * @param createdAt        建单时刻（队列排序第三键=候床时长基准）
 */
public record AdmissionVO(
        String admissionNo,
        Long patientId,
        String sourceType,
        String sourceVisitId,
        String targetDeptId,
        String targetWardId,
        Long targetBedId,
        String admissionType,
        LocalDate expectDate,
        String diagnosisSummary,
        String issuedDoctorId,
        String status,
        OffsetDateTime createdAt) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 住院证行，非空
     * @return 出参，非空
     */
    public static AdmissionVO from(Admission entity) {
        return new AdmissionVO(
                entity.getAdmissionNo(),
                entity.getPatientId(),
                entity.getSourceType(),
                entity.getSourceVisitId(),
                entity.getTargetDeptId(),
                entity.getTargetWardId(),
                entity.getTargetBedId(),
                entity.getAdmissionType(),
                entity.getExpectDate(),
                entity.getDiagnosisSummary(),
                entity.getIssuedDoctorId(),
                entity.getStatus(),
                entity.getCreatedAt());
    }
}

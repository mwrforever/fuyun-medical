package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.InpatientVisit;
import java.time.OffsetDateTime;

/**
 * 住院就诊出参（登记确认/入科确认共用；实体禁直出——出网边界唯一出口）。字段面与 V902
 * inpatient_visit 冻结列面一一对应；出院时点/离院方式/欠费标识由后续任务（Task 9/10）写入。
 *
 * @param visitId             住院就诊号（I 型 14 位，string 承载；签发后不可变不可复用）
 * @param admissionId         关联住院证 id（admission 1:0..1 inpatient_visit）
 * @param patientId           患者主索引（签发时点归一主档）
 * @param currentDeptId       当前科室编码（入科确认写入）
 * @param currentWardId       当前病区编码（入科确认写入）
 * @param currentBedId        当前床位 id（入科确认写入；转科/转床变更；bed 表见 V903）
 * @param attendingDoctorId   主治医生（入科确认写入）
 * @param nursingLevel        护理级别（SPECIAL/CRITICAL/NORMAL；权威在本域，M05 为视图镜像）
 * @param insuranceType       医保类型（M01 字典 code；register 登记并随事件外发）
 * @param admissionDiagnosis  入院诊断（register 自住院证誊写；敏感文本，仅回显不外发事件）
 * @param registeredAt        登记确认时点（visit_id 签发时点）
 * @param admittedAt          入科确认时点（库端 now()；未入科为 null）
 * @param dischargeRequestedAt 出院申请时点（Task 9 写入；未申请为 null）
 * @param dischargedAt        出院完成时点（Task 9 写入；未出院为 null）
 * @param dischargeWay        离院方式（病案首页代码，Task 9 写入）
 * @param arrearsFlag         欠费标识（Task 10 消费 billing.deposit.changed 刷新；缺省 false）
 * @param status              状态 code（VisitStatus：REGISTERED/ADMITTED/DISCHARGE_REQUESTED/DISCHARGED/CANCELLED）
 */
public record InpatientVisitVO(
        String visitId,
        Long admissionId,
        Long patientId,
        String currentDeptId,
        String currentWardId,
        Long currentBedId,
        String attendingDoctorId,
        String nursingLevel,
        String insuranceType,
        String admissionDiagnosis,
        OffsetDateTime registeredAt,
        OffsetDateTime admittedAt,
        OffsetDateTime dischargeRequestedAt,
        OffsetDateTime dischargedAt,
        String dischargeWay,
        Boolean arrearsFlag,
        String status) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 住院就诊行，非空
     * @return 出参，非空
     */
    public static InpatientVisitVO from(InpatientVisit entity) {
        return new InpatientVisitVO(
                entity.getVisitId(),
                entity.getAdmissionId(),
                entity.getPatientId(),
                entity.getCurrentDeptId(),
                entity.getCurrentWardId(),
                entity.getCurrentBedId(),
                entity.getAttendingDoctorId(),
                entity.getNursingLevel(),
                entity.getInsuranceType(),
                entity.getAdmissionDiagnosis(),
                entity.getRegisteredAt(),
                entity.getAdmittedAt(),
                entity.getDischargeRequestedAt(),
                entity.getDischargedAt(),
                entity.getDischargeWay(),
                entity.getArrearsFlag(),
                entity.getStatus());
    }
}

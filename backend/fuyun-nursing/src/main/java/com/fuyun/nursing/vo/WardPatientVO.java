package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.NursingWardPatient;
import java.time.OffsetDateTime;

/**
 * 病区患者一览行出参（GC39 字段命名逐字对齐 V800 id48 desc：visitId/patientId/wardId/bedNo/
 * nursingLevel/admittedAt，禁改名）。病区一览与登记/移出确认共用（remove 零回读语义下仅携
 * visitId 定位锚，其余组件为 null）。
 *
 * @param visitId      住院就诊号（I 型 14 位）
 * @param patientId    患者主索引（MERGED 收敛主档口径）
 * @param wardId       病区编码
 * @param bedNo        床位号
 * @param nursingLevel 护理级别（NursingLevel code）
 * @param admittedAt   入区时间
 */
public record WardPatientVO(
        String visitId, Long patientId, String wardId, String bedNo, String nursingLevel, OffsetDateTime admittedAt) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 病区患者视图行，非空
     * @return 一览行出参，非空
     */
    public static WardPatientVO from(NursingWardPatient entity) {
        return new WardPatientVO(
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getBedNo(),
                entity.getNursingLevel(),
                entity.getAdmittedAt());
    }
}

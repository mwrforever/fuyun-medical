package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.NursingWardPatient;
import java.time.OffsetDateTime;

/**
 * 病区患者一览行出参（GC39 字段命名逐字对齐 V800 id48 desc：visitId/patientId/wardId/bedNo/
 * nursingLevel/admittedAt，禁改名；W-34 退役核验断言③的 record 组件清单 equals 冻结面）。
 * <b>六字段与四事件载荷可推导性逐字段锚定（GC39 结构等价判据，W-34 退役核验）</b>：
 * visitId ← admitted/transferred/discharged 载荷定位键；patientId ← admitted.patientId；
 * wardId ← admitted.wardId（转科随 transferred.toWardId 刷新）；bedNo ← bed.changed.bedNo
 * （admitted/transferred 载荷仅携床位 id 无床号，床号文本唯一写入面=bed.changed 补齐）；
 * nursingLevel ← admitted.nursingLevel；admittedAt ← admitted.admittedAt——六字段全部可由
 * 四事件载荷推导，读面结构等价成立。
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
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.7-4 先例）。
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

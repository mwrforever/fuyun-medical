package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.MedicalOrder;
import java.time.OffsetDateTime;

/**
 * 转抄工作台待转抄行出参（GET /api/v1/inpatient/transfer-worklist）：AUDITED 医嘱按病区/班次
 * 聚合的待转抄列表行（实体禁直出——出网边界唯一出口）。highRisk 为双人核对强制面提示
 * （输血类 BLOOD=true——工作台据此强制第二核对人录入；item 级高危药标记 V904 无落列，
 * 药品高危分级字典对接为 P3 注记）。脱敏红线：不含患者姓名/诊断文本。
 *
 * @param orderNo     医嘱号，非空
 * @param visitId     住院就诊号（I 型 14 位），非空
 * @param patientId   患者主索引，非空
 * @param orderType   医嘱类型 code（OrderType 九值），非空
 * @param orderClass  医嘱分类 code（LONG/STAT），非空
 * @param standbyFlag 备用嘱（嘱托）标记；仅 LONG 可 true
 * @param freqCode    频次编码（长期医嘱非空、临时医嘱 null）
 * @param doctorId    开立医生，非空
 * @param orderedAt   开立时点，非空（班次窗口过滤锚）
 * @param highRisk    双人核对强制面（true=输血类医嘱，缺第二核对人拒 IP-1016），非空
 */
public record TransferWorklistVO(
        String orderNo,
        String visitId,
        Long patientId,
        String orderType,
        String orderClass,
        Boolean standbyFlag,
        String freqCode,
        String doctorId,
        OffsetDateTime orderedAt,
        boolean highRisk) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）；
     * highRisk 按医嘱类型裁决（BLOOD 输血类=强制双人核对）。
     *
     * @param entity  待转抄医嘱行，非空
     * @param visitNo 住院就诊号（I 型 14 位，病区聚合查询上下文已知），非空
     * @return 出参，非空
     */
    public static TransferWorklistVO from(MedicalOrder entity, String visitNo) {
        return new TransferWorklistVO(
                entity.getOrderNo(),
                visitNo,
                entity.getPatientId(),
                entity.getOrderType(),
                entity.getOrderClass(),
                entity.getStandbyFlag(),
                entity.getFreqCode(),
                entity.getDoctorId(),
                entity.getOrderedAt(),
                "BLOOD".equals(entity.getOrderType()));
    }
}

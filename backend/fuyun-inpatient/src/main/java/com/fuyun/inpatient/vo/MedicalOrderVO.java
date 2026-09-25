package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.MedicalOrder;
import java.time.OffsetDateTime;

/**
 * 住院医嘱出参（列表行与开立回执共用；实体禁直出——出网边界唯一出口）。字段面与 V904
 * medical_order 冻结列面一一对应（visitId 为 I 型 14 位号出参形态——表内 visit_id 存
 * inpatient_visit 主键，出参经就诊行关联号映射，与事件载荷口径一致）。
 *
 * @param orderNo    医嘱号（MO+yyyyMMdd+5 位流水），非空
 * @param visitId    住院就诊号（I 型 14 位），非空
 * @param patientId  患者主索引，非空
 * @param orderType  医嘱类型 code（OrderType 九值），非空
 * @param orderClass 医嘱分类 code（LONG/STAT），非空
 * @param standbyFlag 备用嘱（嘱托）标记；仅 LONG 可 true
 * @param groupNo    成组医嘱组号（单条医嘱=order_no 缺省形态），非空
 * @param freqCode   频次编码（长期医嘱非空、临时医嘱 null）
 * @param beginAt    医嘱生效起始时点（审核通过面写入，开立时 null）
 * @param endAt      停嘱时点（未停为 null）
 * @param doctorId   开立医生（M01 用户标识），非空
 * @param orderedAt  开立时点，非空
 * @param stopReason 停嘱原因（未停为 null）
 * @param status     状态 code（OrderStatus 八态），非空
 */
public record MedicalOrderVO(
        String orderNo,
        String visitId,
        Long patientId,
        String orderType,
        String orderClass,
        Boolean standbyFlag,
        String groupNo,
        String freqCode,
        OffsetDateTime beginAt,
        OffsetDateTime endAt,
        String doctorId,
        OffsetDateTime orderedAt,
        String stopReason,
        String status) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity  医嘱行，非空
     * @param visitNo 住院就诊号（I 型 14 位，查询/开立上下文已知），非空
     * @return 出参，非空
     */
    public static MedicalOrderVO from(MedicalOrder entity, String visitNo) {
        return new MedicalOrderVO(
                entity.getOrderNo(),
                visitNo,
                entity.getPatientId(),
                entity.getOrderType(),
                entity.getOrderClass(),
                entity.getStandbyFlag(),
                entity.getGroupNo(),
                entity.getFreqCode(),
                entity.getBeginAt(),
                entity.getEndAt(),
                entity.getDoctorId(),
                entity.getOrderedAt(),
                entity.getStopReason(),
                entity.getStatus());
    }
}

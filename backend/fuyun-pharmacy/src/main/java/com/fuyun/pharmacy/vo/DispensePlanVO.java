package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.DispensePlan;
import java.time.OffsetDateTime;

/**
 * 住院摆药计划出参（from(entity) 手写映射，DispenseVO 同型——关键业务字段禁 MapTransform）。
 * 时间线字段 issuedAt/deliveredAt 随行直出（摆药工作台进度面消费，区别于门诊 DispenseVO
 * 审计时刻不出网口径——住院签收时点为 M05 签收衔接数据面）。
 */
public record DispensePlanVO(
        Long id,
        String planNo,
        String m04OrderNo,
        String visitId,
        Long patientId,
        String wardId,
        String planType,
        OffsetDateTime planTime,
        String status,
        String pivasBatchNo,
        Boolean labelPrinted,
        Long pickedBy,
        Long verifiedBy,
        OffsetDateTime issuedAt,
        OffsetDateTime deliveredAt,
        Long receivedBy,
        String cancelReason) {

    /**
     * 实体→出参静态工厂。
     *
     * @param entity 摆药计划行，非空
     * @return 出参，非空
     */
    public static DispensePlanVO from(DispensePlan entity) {
        return new DispensePlanVO(
                entity.getId(),
                entity.getPlanNo(),
                entity.getM04OrderNo(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getPlanType(),
                entity.getPlanTime(),
                entity.getStatus(),
                entity.getPivasBatchNo(),
                entity.getLabelPrinted(),
                entity.getPickedBy(),
                entity.getVerifiedBy(),
                entity.getIssuedAt(),
                entity.getDeliveredAt(),
                entity.getReceivedBy(),
                entity.getCancelReason());
    }
}

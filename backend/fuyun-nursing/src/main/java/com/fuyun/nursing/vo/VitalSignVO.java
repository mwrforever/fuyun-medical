package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.VitalSignRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 生命体征记录出参（录入/查询/待复核清单/复核转正/驳回共用回读面）。iotQuality/conflictRef
 * 为 P2 IoT 写入方预留回读列（P1 恒空，列面先行冻结防后续契约变更）；abnormalFlag 为录入时
 * 阈值判定结果快照。
 *
 * @param id           体征记录 id
 * @param visitId      住院就诊号
 * @param patientId    患者主索引
 * @param wardId       病区编码
 * @param measuredAt   测量时点（服务器时间）
 * @param temperature  体温（℃，未测为 null）
 * @param tempSite     体温部位（TempSite code，未测体温为 null）
 * @param pulse        脉搏（次/分，未测为 null）
 * @param respiration  呼吸（次/分，未测为 null）
 * @param systolicBp   收缩压（mmHg，未测为 null）
 * @param diastolicBp  舒张压（mmHg，未测为 null）
 * @param spo2         血氧饱和度（%，未测为 null）
 * @param weight       体重（kg，未测为 null）
 * @param height       身高（cm，未测为 null）
 * @param painScore    疼痛评分（NRS 0-10，未评为 null）
 * @param source       数据源（VitalSource code：MANUAL/PDA/IOT）
 * @param reviewStatus 复核状态（VitalReviewStatus code：PENDING_REVIEW/CONFIRMED/REJECTED）
 * @param reviewedBy   复核人（录入即 CONFIRMED 时为录入操作者）
 * @param reviewedAt   复核时间
 * @param abnormalFlag 是否越正常范围（判定结果快照）
 * @param iotQuality   IoT 质量标记（P2 写入方，P1 恒空）
 * @param conflictRef  同窗冲突对参照记录（P2 写入方，P1 恒空）
 * @param remark       备注（驳回原因等）
 */
public record VitalSignVO(
        Long id,
        String visitId,
        Long patientId,
        String wardId,
        OffsetDateTime measuredAt,
        BigDecimal temperature,
        String tempSite,
        Integer pulse,
        Integer respiration,
        Integer systolicBp,
        Integer diastolicBp,
        Integer spo2,
        BigDecimal weight,
        BigDecimal height,
        Integer painScore,
        String source,
        String reviewStatus,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        Boolean abnormalFlag,
        String iotQuality,
        Long conflictRef,
        String remark) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 体征记录行，非空
     * @return 体征记录出参，非空
     */
    public static VitalSignVO from(VitalSignRecord entity) {
        return new VitalSignVO(
                entity.getId(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getMeasuredAt(),
                entity.getTemperature(),
                entity.getTempSite(),
                entity.getPulse(),
                entity.getRespiration(),
                entity.getSystolicBp(),
                entity.getDiastolicBp(),
                entity.getSpo2(),
                entity.getWeight(),
                entity.getHeight(),
                entity.getPainScore(),
                entity.getSource(),
                entity.getReviewStatus(),
                entity.getReviewedBy(),
                entity.getReviewedAt(),
                entity.getAbnormalFlag(),
                entity.getIotQuality(),
                entity.getConflictRef(),
                entity.getRemark());
    }
}

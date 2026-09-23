package com.fuyun.nursing.vo;

import com.fuyun.nursing.entity.IoSummary;
import java.time.OffsetDateTime;

/**
 * 出入量小结出参（班次小结/24h 总结/小结清单/幂等命中回读共用面）。totalIntake/totalOutput/
 * balance 为 string 承载（D-18 口径，数值经 toPlainString 输出，两位小数如 "1500.00"——
 * Task 11 IT 断言锚）；chartEntryRef 为体温单 DAILY_VALUE 条目引用（红双线标识由前端渲染）。
 *
 * @param id            小结 id
 * @param visitId       住院就诊号
 * @param patientId     患者主索引
 * @param wardId        病区编码
 * @param summaryType   小结类型（IoSummaryType code：SHIFT/24H）
 * @param periodStart   统计周期起（含）
 * @param periodEnd     统计周期止（不含）
 * @param totalIntake   总入量（数字文本，两位小数）
 * @param totalOutput   总出量（数字文本，两位小数）
 * @param balance       平衡值 = 总入量 - 总出量（数字文本，两位小数）
 * @param shiftCode     班次 code（24H 恒空）
 * @param chartEntryRef 体温单 DAILY_VALUE 条目引用
 * @param recorderId    记录人
 */
public record IoSummaryVO(
        Long id,
        String visitId,
        Long patientId,
        String wardId,
        String summaryType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        String totalIntake,
        String totalOutput,
        String balance,
        String shiftCode,
        Long chartEntryRef,
        String recorderId) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例；
     * 三数量经 toPlainString 承载 D-18 string 出参口径）。
     *
     * @param entity 出入量小结行，非空
     * @return 出入量小结出参，非空
     */
    public static IoSummaryVO from(IoSummary entity) {
        return new IoSummaryVO(
                entity.getId(),
                entity.getVisitId(),
                entity.getPatientId(),
                entity.getWardId(),
                entity.getSummaryType(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getTotalIntake().toPlainString(),
                entity.getTotalOutput().toPlainString(),
                entity.getBalance().toPlainString(),
                entity.getShiftCode(),
                entity.getChartEntryRef(),
                entity.getRecorderId());
    }
}

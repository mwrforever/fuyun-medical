package com.fuyun.patient.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 健康档案摘要出参（GET /patients/{patientId}/health-summary；无聚合行时为空摘要，items 查全量）。
 *
 * @param patientId        患者主索引
 * @param bloodType        血型（字典 code，可空）
 * @param rhType           RH 血型 POSITIVE/NEGATIVE（可空）
 * @param pastHistory      既往史（可空）
 * @param familyHistory    家族史（可空）
 * @param summaryUpdatedAt 摘要最近变更时刻（事件载荷时间锚点，可空）
 * @param items            明细清单（含 CORRECTED 留痕行，供纠错链展示）
 */
public record HealthSummaryVO(
        Long patientId,
        String bloodType,
        String rhType,
        String pastHistory,
        String familyHistory,
        OffsetDateTime summaryUpdatedAt,
        List<HealthItemVO> items) {}

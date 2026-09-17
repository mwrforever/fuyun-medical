package com.fuyun.patient.vo;

import java.time.LocalDate;

/**
 * 健康档案明细出参（新增/纠错回参与摘要 items 元素；correctOfItemId 供纠错链留痕展示）。
 *
 * @param id              明细 id
 * @param itemType        项类型 ALLERGY/CHRONIC/SURGERY/VACCINATION
 * @param itemCode        字典 code（可空=手工项）
 * @param itemName        项目名称（录入原文）
 * @param severity        严重程度（可空）
 * @param onsetDate       发生日期（可空）
 * @param status          状态 ACTIVE/CORRECTED
 * @param source          来源 DOCTOR_STATION/MANUAL
 * @param note            备注（纠错行为纠错说明）
 * @param correctOfItemId 纠错链回链（本行是被纠错行的重录，首录为空）
 */
public record HealthItemVO(
        Long id,
        String itemType,
        String itemCode,
        String itemName,
        String severity,
        LocalDate onsetDate,
        String status,
        String source,
        String note,
        Long correctOfItemId) {}

package com.fuyun.system.vo;

import java.time.LocalDate;

/**
 * 执业授权清单出参（GET /api/v1/system/practice/grants，FU-M01-04）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。status 为展示态：库值（EFFECTIVE/SUSPENDED）
 * 或读侧派生 EXPIRED（valid_to 已过的 EFFECTIVE 行，不回写库——deriveStatus 先例）。
 *
 * @param id          授权行主键；Long 经全局 Long→String 定制以 JSON 字符串输出
 * @param employeeId  员工 ID；同上以字符串承载
 * @param grantType   授权类型词表值，非空
 * @param legalBasis  法定依据，可空
 * @param validFrom   生效日（含当日），非空
 * @param validTo     失效日（含当日），可空（NULL=长期有效）
 * @param status      展示状态（EFFECTIVE/SUSPENDED/EXPIRED），非空
 * @param approvalRef 审批引用（医务审批单号），可空
 */
public record PracticeGrantVO(
        Long id,
        Long employeeId,
        String grantType,
        String legalBasis,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        String approvalRef) {}

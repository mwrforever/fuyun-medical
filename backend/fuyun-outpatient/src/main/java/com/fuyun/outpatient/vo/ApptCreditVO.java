package com.fuyun.outpatient.vo;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 爽约信用记录出参（GET /appt-credits?patientId= 与 POST /appt-credits/{id}/release 直出形态）：
 * 信用台账管理面投影——窗口计数、限约区间与解除留痕（Task 6）。
 *
 * @param id            信用记录主键
 * @param patientId     患者主索引
 * @param action        动作词表（NO_SHOW 爽约超时 / TIMEOUT_CANCEL 时限外取消）
 * @param occurredAt    发生时刻
 * @param windowDays    记录时采用的统计窗口天数
 * @param restrictFrom  限约起始日（未达阈值为 null）
 * @param restrictTo    限约截止日（含当日；手工解除后=今日-1 即提前失效）
 * @param releaseReason 解除原因（手工解除留痕；未解除为 null）
 */
public record ApptCreditVO(
        Long id,
        Long patientId,
        String action,
        OffsetDateTime occurredAt,
        Integer windowDays,
        LocalDate restrictFrom,
        LocalDate restrictTo,
        String releaseReason) {}

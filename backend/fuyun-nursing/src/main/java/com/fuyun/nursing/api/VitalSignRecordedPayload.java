package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 体征记录转正入卡事件载荷（nursing.vital-sign.recorded，V800 id 56 冻结契约，Task 5 发布）：
 * 体征由待审状态转正入体温单/观察行归集时发布，M09/M11/M19 可能取数。
 *
 * @param patientId    患者主索引，非空；来源：体征记录所属就诊关联
 * @param visitId      就诊标识（住院就诊号），非空
 * @param measuredAt   测量时点（UTC），非空；来源：护士站录入/设备采集时刻
 * @param source       测量来源（人工录入/设备采集等形态标识），非空
 * @param reviewStatus 审核状态（转正入卡时的复核状态），非空
 * @param abnormal     是否越正常范围（驱动观察行归集），true=异常
 */
public record VitalSignRecordedPayload(
        long patientId, String visitId, Instant measuredAt, String source, String reviewStatus, boolean abnormal) {}

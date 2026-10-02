package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 拔针/输注结束事件载荷（nursing.infusion.completed，V800 id 63 冻结契约，Task 6 发布）：
 * 输注结束（拔针确认）时发布，M14 停止监测、护理域自动生成输液入量行（FU-M05-06）。
 *
 * @param executionNo 执行单业务号，非空；来源：输液类执行单（M14 停止监测幂等锚）
 * @param patientId   患者主索引，非空；来源：执行单所属就诊关联
 * @param visitId     就诊标识（住院就诊号），非空
 * @param endedAt     输注结束时点（UTC），非空；来源：拔针确认操作落库时点
 */
public record InfusionCompletedPayload(String executionNo, long patientId, String visitId, Instant endedAt) {}

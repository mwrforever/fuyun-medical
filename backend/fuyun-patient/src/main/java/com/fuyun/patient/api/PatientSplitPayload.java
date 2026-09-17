package com.fuyun.patient.api;

/**
 * patient.patient.split 载荷（V105 id=12 冻结契约）：拆分恢复广播，patient.patient.merged 的逆事件（一一成对，M-25）。
 *
 * @param restoredPatientId 恢复 NORMAL 的原从档 id；来源：拆分流程
 * @param survivorPatientId 原主档 id（订阅方按需回收归一映射）；来源：merge_record
 */
public record PatientSplitPayload(long restoredPatientId, long survivorPatientId) {}

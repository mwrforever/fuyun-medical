package com.fuyun.patient.vo;

/**
 * 标识解析结果出参（api PatientContextView 的 REST 形态；前端以 string 承载 id）。
 *
 * @param patientId         入参解析锚点（归一前）
 * @param resolvedPatientId 归一主档 id
 * @param status            主档状态 NORMAL/FROZEN/MERGED
 * @param blocked           拦截标记（业务模块据此拒绝新就诊）
 * @param blockReason       拦截原因（空串=无）
 */
public record ResolveVO(long patientId, long resolvedPatientId, String status, boolean blocked, String blockReason) {}

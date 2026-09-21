package com.fuyun.patient.api;

/**
 * 患者脱敏展示名视图（record 透明浅不可变，backend 宪法 A.1-2）：{@link PatientNameQuery}
 * 的返回行——displayName 已按 M02 脱敏规则（保留姓氏）处理，调用方直接出网，禁二次拼装原文。
 *
 * @param patientId   患者主索引（归一主档 id）；来源：调用方入参集合的命中项
 * @param displayName 脱敏展示名（如 张*）；来源：patient.patient.name 经 SensitiveMasker.maskName
 */
public record PatientDisplayName(long patientId, String displayName) {}

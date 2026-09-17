package com.fuyun.patient.api;

/**
 * patient.patient.unfrozen 载荷（V105 id=14 冻结契约）：解冻广播，patient.patient.frozen 的逆事件（一一成对，M-25）。
 *
 * @param patientId 解冻患者 id；来源：解冻端点
 */
public record PatientUnfrozenPayload(long patientId) {}

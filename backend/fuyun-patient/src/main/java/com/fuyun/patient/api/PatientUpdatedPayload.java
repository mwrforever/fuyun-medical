package com.fuyun.patient.api;

import java.util.List;

/**
 * patient.updated 载荷（V105 id=10 冻结契约）：主数据变更广播，仅携变更字段名清单不携值。
 *
 * @param patientId     患者 id；来源：本模块
 * @param changedFields 变更字段名清单（如 ["mobile","address"]，字段名与实体属性一致）；来源：更新流程比对
 */
public record PatientUpdatedPayload(long patientId, List<String> changedFields) {}

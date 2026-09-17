package com.fuyun.patient.api;

/**
 * 患者上下文解析结果（record 透明浅不可变，backend 宪法 A.1-2）：归一主档视图 + 拦截标记。
 *
 * @param patientId         调用方入参的原始患者 id；来源：调用方
 * @param resolvedPatientId 归一后的主档 id（从档解析时为主档；正常档案时与 patientId 相同）；
 *                          来源：patient.patient.merged_into_patient_id 指针收敛
 * @param status            归一后主档状态：NORMAL/FROZEN/MERGED；来源：patient.status
 * @param blocked           是否拦截新就诊（true=FROZEN 冻结中；MERGED 已收敛主档不拦截）；来源：状态机
 * @param blockReason       拦截原因（blocked=false 时空串）；来源：冻结原因，供业务模块提示
 */
public record PatientContextView(
        long patientId, long resolvedPatientId, String status, boolean blocked, String blockReason) {}

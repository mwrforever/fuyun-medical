package com.fuyun.patient.api;

/**
 * patient.identifier.changed 载荷（V105 id=15 冻结契约）：标识变更广播（绑卡/挂失/补卡/解绑），
 * 解析缓存失效依据。敏感红线：valueHash 为 HMAC 盲索引摘要，禁带标识值明文。
 *
 * @param patientId      患者 id；来源：标识操作流程
 * @param identifierType 标识类型（与 patient_identifier.identifier_type 同词表）；来源：标识行
 * @param valueHash      标识值 HMAC 盲索引（缓存键）；来源：标识行
 * @param changeType     变更类型 BOUND 绑定/LOST 挂失/REPLACED 补卡替换/UNBOUND 解绑；来源：状态机
 */
public record PatientIdentifierChangedPayload(
        long patientId, String identifierType, String valueHash, String changeType) {}

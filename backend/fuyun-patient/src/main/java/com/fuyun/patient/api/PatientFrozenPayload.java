package com.fuyun.patient.api;

/**
 * patient.frozen 载荷（V105 id=13 冻结契约）：冻结广播；成对语义（M-25）——订阅方必须成对登记
 * 订阅 patient.unfrozen。冻结期间解析视图 blocked=true，业务模块拒绝新就诊。
 *
 * @param patientId 冻结患者 id；来源：冻结端点
 * @param reason    冻结原因（身份存疑/风控要求等业务描述，非敏感字段）；来源：冻结请求
 */
public record PatientFrozenPayload(long patientId, String reason) {}

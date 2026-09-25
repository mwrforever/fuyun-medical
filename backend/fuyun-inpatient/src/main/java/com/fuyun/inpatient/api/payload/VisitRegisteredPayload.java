package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 入院登记事件载荷（inpatient.visit.registered，V901 id 65 冻结契约）：住院证登记确认
 * （同事务签发 I 型 visit_id）完成时发布，M13 据此办理医保入院登记。
 *
 * @param visitId       住院就诊号（I 型 visit_id，入院域签发），非空；来源：InpatientSeqGate 同事务签发
 * @param patientId     患者主索引，非空；来源：住院证关联患者
 * @param admissionNo   住院证号，非空；来源：入院登记域业务号
 * @param registeredAt  登记时点（UTC），非空；来源：登记确认操作时刻
 * @param insuranceType 医保类型（险种标识），非空；来源：住院证登记面
 */
public record VisitRegisteredPayload(
        String visitId, long patientId, String admissionNo, Instant registeredAt, String insuranceType) {}

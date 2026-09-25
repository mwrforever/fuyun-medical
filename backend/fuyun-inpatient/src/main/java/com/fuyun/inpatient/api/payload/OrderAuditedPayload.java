package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱审核通过事件载荷（inpatient.order.audited，V800 id 41 冻结契约）：系统自动审核
 * （非用药类开立后过审）与药师审方回执（M06 通过回执驱动）两路径共用；routing key 携带
 * 类型子键（audited.lab/audited.drug 等，登记名不带子键，R3-06）。M13 撮此即时计价
 * （住院药费唯一计价触发）。脱敏红线：仅定位键与时间线，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo    医嘱号，非空；来源：过审医嘱业务号
 * @param visitId       住院就诊号（I 型 14 位），非空；来源：医嘱关联就诊
 * @param patientId     患者主索引，非空
 * @param auditType     审核类型（AuditStage code：SYSTEM 系统自动审核/PHARMACIST 药师审方），非空
 * @param auditOperator 审核操作者（员工 ID string；SYSTEM=开立医生、PHARMACIST=审方药师），非空
 * @param auditedAt     审核通过时点（UTC；PHARMACIST 路径取 M06 回执时点），非空
 */
public record OrderAuditedPayload(
        String m04OrderNo, String visitId, long patientId, String auditType, String auditOperator, Instant auditedAt) {}

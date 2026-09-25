package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱审核驳回事件载荷（inpatient.order.audit-rejected，V901 id 67 冻结契约）：
 * M06 药师审方驳回回执（pharmacy.medication-order.audit-rejected）消费驱动医嘱置驳回态后发布，
 * 医生站据此走修改重提路径。
 *
 * @param m04OrderNo  医嘱号，非空；来源：被驳回医嘱业务号
 * @param visitId     住院就诊号，非空
 * @param patientId   患者主索引，非空
 * @param rejectReason 驳回原因（药师意见），非空；来源：M06 回执载荷
 * @param rejectedAt  驳回时点（UTC），非空；来源：M06 回执时点
 */
public record OrderAuditRejectedPayload(
        String m04OrderNo, String visitId, long patientId, String rejectReason, Instant rejectedAt) {}

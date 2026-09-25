package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 患者入科事件载荷（inpatient.visit.admitted，V800 id 48 冻结契约）：入科确认（visit
 * REGISTERED→ADMITTED，床位/科室/护理级别登记）完成时发布，M05 据此维护病区患者本地视图、
 * M14 触发设备待绑定提醒。
 *
 * @param visitId      住院就诊号（I 型 visit_id，入院域签发），非空；来源：入科确认的就诊行
 * @param patientId    患者主索引，非空；来源：就诊行归一主档
 * @param wardId       入科病区编码，非空；来源：入科确认入参
 * @param bedId        入科床位 id，非空；来源：入科确认入参（床位流转权威归 bed 域，bed.changed 由联动面发布）
 * @param admittedAt   入科确认时点（UTC），非空；来源：库端 now() 回读
 * @param nursingLevel 护理级别（SPECIAL/CRITICAL/NORMAL），非空；来源：入科确认入参
 */
public record VisitAdmittedPayload(
        String visitId, long patientId, String wardId, long bedId, Instant admittedAt, String nursingLevel) {}

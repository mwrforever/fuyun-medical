package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 患者出院终态事件载荷（inpatient.visit.discharged，V800 id 51 冻结契约）：离院确认（双条件
 * =预审 READY+出院结算完成标记均满足，GC19 三重校验后置位）完成时发布，M05 撮此终清在途
 * 任务与执行单、M14 撮此强制解绑设备、M19 撮此统计入池。脱敏红线：仅定位键与时间线，
 * 禁患者姓名/诊断文本。
 *
 * @param visitId      住院就诊号（I 型 14 位），非空；来源：离院确认的就诊行
 * @param patientId    患者主索引，非空；来源：就诊行归一主档
 * @param dischargedAt 出院完成时点（UTC），非空；来源：库端 now() 回读（casDischarge）
 */
public record VisitDischargedPayload(String visitId, long patientId, Instant dischargedAt) {}

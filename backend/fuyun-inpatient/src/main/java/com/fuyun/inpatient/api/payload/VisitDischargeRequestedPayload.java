package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 出院申请事件载荷（inpatient.visit.discharge-requested，V800 id 50 冻结契约）：医生评估后
 * 提交出院申请（visit→DISCHARGE_REQUESTED，在途清理编排与费用预审完成）时发布，M05 撮此
 * 清退在途任务提示、M13 撮此停止计费并做出院预审。脱敏红线：仅定位键与时间线，禁患者
 * 姓名/诊断文本。
 *
 * @param visitId     住院就诊号（I 型 14 位），非空；来源：出院申请的就诊行
 * @param patientId   患者主索引，非空；来源：就诊行归一主档
 * @param requestedAt 出院申请时点（UTC），非空；来源：库端 now() 回读（casRequestDischarge）
 */
public record VisitDischargeRequestedPayload(String visitId, long patientId, Instant requestedAt) {}

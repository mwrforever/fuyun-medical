package com.fuyun.patient.api;

import java.util.List;

/**
 * patient.health-summary.updated 载荷（V105 id=16 冻结契约）：健康档案变更广播（含过敏项摘要）；
 * M06 审方预检缓存刷新、M05 护理执行、M03/M04 开单场景过敏与禁忌提示依据。
 *
 * @param patientId    患者 id；来源：健康档案维护流程
 * @param hasAllergy   当前是否仍有有效过敏项；来源：health_item 聚合
 * @param allergyCodes 有效过敏项字典 code 清单（无 code 的手工项不入清单，仅以 hasAllergy 表达）；
 *                     来源：health_item（ACTIVE + ALLERGY）
 */
public record PatientHealthSummaryUpdatedPayload(long patientId, boolean hasAllergy, List<String> allergyCodes) {}

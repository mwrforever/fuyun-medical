package com.fuyun.nursing.api;

/**
 * 护理评估完成事件载荷（nursing.assessment.completed，V800 id 57 冻结契约，Task 8 发布）：
 * 护理评估单评分落库完成时发布，高危结论联动防范任务生成。
 *
 * @param patientId  患者主索引，非空；来源：评估单所属就诊关联
 * @param visitId    就诊标识（住院就诊号），非空
 * @param assessNo   评估单业务号，非空；来源：护理评估发号器
 * @param scaleType  量表类型（跌倒/压疮/疼痛等评估量表标识），非空
 * @param totalScore 量表总分，非负；来源：评估条目计分聚合
 * @param riskLevel  风险等级（按量表判级规则评定），非空；高危等级驱动防范任务联动
 */
public record AssessmentCompletedPayload(
        long patientId, String visitId, String assessNo, String scaleType, int totalScore, String riskLevel) {}

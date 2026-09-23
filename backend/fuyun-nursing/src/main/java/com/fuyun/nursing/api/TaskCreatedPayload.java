package com.fuyun.nursing.api;

/**
 * 护理任务生成事件载荷（nursing.task.created，V800 id 58 冻结契约，Task 7 发布）：
 * 护理任务落库时发布，M14 联动规则与 M16 紧急呼叫经 POST /tasks 幂等创建消费。
 *
 * @param taskNo    护理任务业务号，非空；来源：护理任务发号器（消费方幂等创建锚）
 * @param patientId 患者主索引，非空；来源：任务所属就诊关联
 * @param visitId   就诊标识（住院就诊号），非空
 * @param taskType  任务类型（翻身/雾化/监测等护理任务分类），非空
 * @param source    任务来源（医嘱转抄/评估联动/手工开立等生成渠道），非空
 */
public record TaskCreatedPayload(String taskNo, long patientId, String visitId, String taskType, String source) {}

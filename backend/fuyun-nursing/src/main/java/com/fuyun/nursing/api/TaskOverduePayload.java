package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 护理任务逾期升级广播载荷（nursing.task.overdue，V800 id 61 冻结契约，Task 9 发布）：
 * delay.task-overdue 延迟档位到期回投触发逾期扫描，逾期任务升级动作广播。
 * V800 desc 冻结五字段（taskNo/patientId/wardId/planTime/escalationCount），本 record 为其中
 * 升级动作必需的三字段投影——taskNo 幂等锚、planTime 逾期判定基准、escalationCount 升级档位。
 *
 * @param taskNo          护理任务业务号，非空；来源：任务发号器（消费方幂等锚）
 * @param planTime        计划时间（UTC），非空；逾期判定基准（来源：任务落库计划时间）
 * @param escalationCount 升级次数，非负；来源：逾期升级动作本次递增后的值（0 起算单次递增）
 */
public record TaskOverduePayload(String taskNo, Instant planTime, int escalationCount) {}

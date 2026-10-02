package com.fuyun.nursing.api;

/**
 * 联动任务创建结果（M14→M05 联动端口出参，P2 PR-3 Task 12）：taskNo 与幂等区分位二字段
 * 最小面——created 区分首建与重放，iot 侧两路均按 SUCCESS 回执（幂等重放视为成功，brief 冻结）。
 *
 * @param taskNo  护理任务业务号（TK 段；首建=新建行任务号，重放=既有行任务号），非空
 * @param created true=本次新建任务行（nursing.task.created 事件已随 create 既有发布）；
 *                false=幂等重放（source=IOT_LINKAGE+source_ref=linkageNo 回查命中既有行，零新建零事件）
 */
public record NursingTaskLinkageResult(String taskNo, boolean created) {}

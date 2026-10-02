package com.fuyun.nursing.vo;

/**
 * 常规模板批量生成出参（P2 PR-3 Task 9）：本轮实际新生成的任务行数（幂等去重后）。
 *
 * @param createdTasks 新生成任务行数（既有业务键行不计入），非负
 */
public record RoutineTaskGenerateVO(int createdTasks) {}

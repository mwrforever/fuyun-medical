package com.fuyun.nursing.dto;

/**
 * 护理任务完成入参（POST /api/v1/nursing/tasks/{taskNo}/complete，P2 PR-3 Task 9 扩参面）：
 * 整体可空请求体（不携单据引用的既有完成语义零变化——兼容旧调用方零请求体直 POST）。
 *
 * @param relatedExecutionNo 关联执行单号（完成时随 CAS 定格 source_ref——任务↔执行单追溯链），
 *                           可空（空=保持原引用零覆盖）；来源：工作站/PDA 完成表单
 */
public record NursingTaskCompleteRequest(String relatedExecutionNo) {}

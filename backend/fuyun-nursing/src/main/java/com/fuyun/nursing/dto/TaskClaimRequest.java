package com.fuyun.nursing.dto;

/**
 * 护理任务认领入参（POST /api/v1/nursing/tasks/{taskNo}/claim，P2 PR-3 Task 9 任务工作台面）：
 * assigneeId 兼容保留——认领人一律登录令牌身份落 assigned_nurse（W-72，2026-10-03 裁决）。
 *
 * @param assigneeId 认领护士员工 ID（兼容保留——服务端一律以令牌身份落值，本字段不再消费），
 *                   可空；来源：任务工作台认领动作
 */
public record TaskClaimRequest(Long assigneeId) {}

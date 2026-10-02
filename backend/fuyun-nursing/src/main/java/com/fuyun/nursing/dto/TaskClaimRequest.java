package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 护理任务认领入参（POST /api/v1/nursing/tasks/{taskNo}/claim，P2 PR-3 Task 9 任务工作台面）：
 * assigneeId 必填（认领后落 assigned_nurse 文本承载）。
 *
 * @param assigneeId 认领护士员工 ID，必填；来源：任务工作台认领动作（当前责任组指派）
 */
public record TaskClaimRequest(
        @NotNull(message = "认领护士员工ID必填（assigneeId）") Long assigneeId) {}

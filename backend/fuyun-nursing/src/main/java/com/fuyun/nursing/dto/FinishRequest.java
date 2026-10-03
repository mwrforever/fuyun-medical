package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 执行单完成入参（POST /api/v1/nursing/executions/{no}/finish）：EXECUTING→COMPLETED +
 * 环节时点落库 + 双路回签（事务内发布 nursing.order-execution.completed + 事务提交后进程内
 * 调回签端口）。INFUSION 型完成由 Task 6 拔针端点（needle-out）承接（携实际入量）。
 *
 * @param executorId       执行护士员工 ID（必填，回执载荷 executorId 承载），非空；来源：PDA 当前登录护士
 * @param routeCheckResult 给药途径核对结论（≤255，可空——回签请求 routeCheckResult 透传），可空；
 *                         来源：PDA 给药途径核对留痕
 */
public record FinishRequest(
        @NotNull(message = "执行护士员工ID必填（executorId）") Long executorId,
        @Size(max = 255, message = "给药途径核对结论超长（≤255）") String routeCheckResult) {}

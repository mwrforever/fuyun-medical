package com.fuyun.nursing.dto;

import jakarta.validation.constraints.Size;

/**
 * 执行单完成入参（POST /api/v1/nursing/executions/{no}/finish）：EXECUTING→COMPLETED +
 * 环节时点落库 + 双路回签（事务内发布 nursing.order-execution.completed + 事务提交后进程内
 * 调回签端口）。INFUSION 型完成由 Task 6 拔针端点（needle-out）承接（携实际入量）。
 *
 * @param executorId       执行护士员工 ID（兼容保留——服务端一律以令牌身份落值，W-72，
 *                         2026-10-03 裁决；本字段不再消费），可空；来源：PDA 当前登录护士
 * @param routeCheckResult 给药途径核对结论（≤255，可空——回签请求 routeCheckResult 透传），可空；
 *                         来源：PDA 给药途径核对留痕
 */
public record FinishRequest(
        Long executorId,
        @Size(max = 255, message = "给药途径核对结论超长（≤255）") String routeCheckResult) {}

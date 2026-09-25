package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 执行回签入参（POST /api/v1/inpatient/order-plans/{no}/execute-confirm，W-33 id 55 字段级
 * 契约逐字）：executorId 必填（执行护士员工 ID——契约明示经请求承载，与操作者上下文
 * OperatorContextHolder 并行口径）；executedAt 可空（缺省服务器时间）；routeCheckResult
 * 可空（给药途径核对结论——静滴/口服等途径核对留痕）。
 *
 * @param executorId       执行护士员工 ID（必填；计划行 executor_id 落值），非空；来源：M05 主路径进程内调用/护士站回签
 * @param executedAt       执行时点（可空=缺省服务器时间；追溯补签场景可显式携带），可空；来源：调用方
 * @param routeCheckResult 给药途径核对结论（可空；V906 列宽 255），可空；来源：执行核对留痕
 */
public record ExecuteConfirmRequest(
        @NotNull(message = "执行护士员工ID必填（executorId）") Long executorId,
        OffsetDateTime executedAt,
        @Size(max = 255, message = "给药途径核对结论超长（≤255）") String routeCheckResult) {}

package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 执行单撤销入参（POST /api/v1/nursing/executions/{no}/cancel）：CREATED/SIGNED/CHECKED→
 * CANCELLED（未执行撤销）；EXECUTING→CANCELLED 仅 INFUSION 型输注中断（护士长权限近似
 * 校验=操作者角色 ∈ ward_config.override_roles，RBAC 面归 PR-4 W-37），携 actualVolumeMl
 * 时按部分执行回签（双路对账 M04 计划）。
 *
 * @param reason         撤销原因（≤255 必填留痕），非空；来源：PDA 撤销表单
 * @param actualVolumeMl 实际入量（毫升，可空——仅输注中断回签面携带，量程校验归 Task 6
 *                       needle-out 端点同款），可空；来源：PDA 中断时护士估读
 */
public record CancelExecutionRequest(
        @NotBlank(message = "撤销原因必填（reason）") @Size(max = 255, message = "撤销原因超长（≤255）")
        String reason,

        Integer actualVolumeMl) {}

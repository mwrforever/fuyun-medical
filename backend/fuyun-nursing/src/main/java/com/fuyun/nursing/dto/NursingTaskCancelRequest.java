package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 护理任务取消入参（POST /api/v1/nursing/tasks/{taskNo}/cancel）。取消原因强制留痕
 * （落 nursing_task.cancel_reason，医疗审计依据）；服务面另有非空强制校验（NS-1019）——
 * 本注解承载 Web 层校验，服务面校验覆盖模块内直调场景。
 *
 * @param reason 取消原因，必填；来源：操作者录入
 */
public record NursingTaskCancelRequest(
        @NotBlank(message = "取消原因不能为空") String reason) {}

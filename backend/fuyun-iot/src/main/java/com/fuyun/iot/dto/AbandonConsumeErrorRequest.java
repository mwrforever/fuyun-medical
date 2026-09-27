package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 消费错误放弃请求（POST /api/v1/iot/consume-errors/{id}/abandon 请求体）：放弃原因强制
 * （V401 状态机申报 ABANDONED 必填原因），原因追加承载于 error_msg 列（V401 无独立原因列，
 * 列宽 500 截断防线）。
 *
 * @param reason 放弃原因，非空白（如"重复帧已由唯一约束去重，人工确认无需重放"）；来源：运维处置
 */
public record AbandonConsumeErrorRequest(
        @NotBlank(message = "放弃原因不能为空") @Size(max = 200, message = "放弃原因最长 200 字符")
        String reason) {}

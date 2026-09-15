package com.fuyun.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 死信关闭请求入参（POST /api/v1/integration/dead-letters/{id}/close，M20 §7）。
 *
 * <p>关闭必填原因（Spec §5 状态机 PENDING → CLOSED 硬要求「必须填写原因，如脏数据放弃」）；
 * record 透明浅不可变载体（A.1-2）+ 声明式校验（A.3-5）。
 *
 * @param handleNote 关闭原因，非空且不超过 500 字符（dead_letter.handle_note 列宽）；来源：运维人工填写
 */
public record DeadLetterCloseRequest(
        @NotBlank @Size(max = 500) String handleNote) {}

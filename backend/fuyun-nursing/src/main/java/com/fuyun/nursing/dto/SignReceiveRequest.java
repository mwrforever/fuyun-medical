package com.fuyun.nursing.dto;

import jakarta.validation.constraints.Size;

/**
 * 执行单人工补签收入参（POST /api/v1/nursing/executions/{no}/sign-receive）：药品类主入口为
 * dispense 事件自动 SIGNED，本端点仅非药品类人工补签。receivedNote 为签收备注（PDA 语音/
 * 手输），V1106 无承载列——经 @AuditLog 请求留痕与日志承载，不落表。
 *
 * @param receivedNote 签收备注（≤255，可空；审计留痕面承载不落表），可空；来源：PDA 补签表单
 */
public record SignReceiveRequest(
        @Size(max = 255, message = "签收备注超长（≤255）") String receivedNote) {}

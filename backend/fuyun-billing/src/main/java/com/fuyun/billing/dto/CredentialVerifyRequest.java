package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医保电子凭证核验请求（POST /insurance/credential，FU-M13-05 接口位；组件清单禁改名改序）：
 * @Valid 边界拒空 + 服务层 BILL-1024 双守卫（禁空凭证静默放行）。
 *
 * @param ecToken 医保电子凭证令牌（扫码输出原文，禁留痕明文）；来源：前端扫码组件
 */
public record CredentialVerifyRequest(@NotBlank String ecToken) {}

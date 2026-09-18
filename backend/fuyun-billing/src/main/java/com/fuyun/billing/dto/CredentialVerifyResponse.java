package com.fuyun.billing.dto;

/**
 * 医保电子凭证核验出参（POST /insurance/credential 直出形态 {"authSerialNo":"SIM-AUTH-…"}；
 * 组件清单禁改名改序）：P5 真实扫码核验替换网关实现后契约零改。
 *
 * @param authSerialNo 核验流水号（模拟="SIM-AUTH-"+凭证令牌确定性派生；真实通道 P5=医保中心核验回执流水）
 */
public record CredentialVerifyResponse(String authSerialNo) {}

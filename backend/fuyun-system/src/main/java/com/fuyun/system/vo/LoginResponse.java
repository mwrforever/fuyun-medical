package com.fuyun.system.vo;

/**
 * 登录/刷新成功响应出参（BRIEF-PR3-01 §1.3 双端点共用同构响应）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。refresh 端点复用本响应：
 * accessToken 为同 sid 换发的新值，refreshToken 原样返回（P0 不做轮换）。
 *
 * @param accessToken  访问令牌（typ=access），非空；两段式 Base64Url(payload).Base64Url(HMAC) 线格式
 * @param refreshToken 刷新令牌（typ=refresh），非空；与 access 同 sid，登出删键即双令牌同失效
 * @param tokenType    令牌方案名，恒为 "Bearer"（RFC 6750，请求头拼接时后接空格）
 * @param expiresIn    access 令牌有效期（秒），非负；来源：fuyun.security.access-token-ttl 换算
 * @param user         登录用户身份，非空；见 {@link UserVO}
 */
public record LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, UserVO user) {}

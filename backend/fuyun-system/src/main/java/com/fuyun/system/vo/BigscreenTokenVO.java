package com.fuyun.system.vo;

/**
 * 大屏订阅令牌签发响应出参（BUG-19：叫号大屏 WS 链路凭证改运行期签发）。
 *
 * <p>与 {@link LoginResponse} 的差异：单 access 短期令牌（无 refresh——大屏不刷新、失效即重取），
 * 不携带用户身份（匿名哨兵会话无身份面）。令牌线格式与登录 access 同构（typ=access），经既有
 * WS CONNECT 帧鉴权链校验——前端拼接 {@code Authorization: Bearer {accessToken}} 注入 CONNECT 帧。
 *
 * @param accessToken 访问令牌（typ=access，短期 TTL），非空；两段式 Base64Url(payload).Base64Url(HMAC)
 * @param tokenType   令牌方案名，恒为 "Bearer"（RFC 6750，请求头拼接时后接空格）
 * @param expiresIn   令牌有效期（秒），正值；客户端据此缓存到期重取（对齐登录响应同名字段口径）
 */
public record BigscreenTokenVO(String accessToken, String tokenType, long expiresIn) {}

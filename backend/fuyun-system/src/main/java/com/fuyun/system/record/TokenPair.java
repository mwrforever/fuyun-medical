package com.fuyun.system.record;

/**
 * 令牌对载体（D-2 签发结果，BRIEF-PR3-01 §3.1 ITokenService 契约）。
 *
 * <p>access 与 refresh 同 sid（共享同一 Redis 会话键）：登出删键即双令牌同时失效。
 * expiresIn（秒）等响应包装字段归 B3.2 登录响应组装（LoginResponse），本对象仅承载原始令牌值。
 *
 * @param accessToken  访问令牌（typ=access），非空；两段式 Base64Url(payload).Base64Url(HMAC) 线格式
 * @param refreshToken 刷新令牌（typ=refresh），非空；P0 不做轮换（同 sid 复用至其 TTL 耗尽）
 */
public record TokenPair(String accessToken, String refreshToken) {}

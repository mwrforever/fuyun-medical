package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求入参（POST /api/v1/system/auth/refresh，BRIEF-PR3-01 §1.3）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 *
 * @param refreshToken 刷新令牌原文（typ=refresh 的两段式令牌值），非空；来源：登录响应留存的前端会话存储
 */
public record RefreshRequest(@NotBlank String refreshToken) {}

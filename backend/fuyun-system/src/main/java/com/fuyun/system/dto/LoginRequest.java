package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求入参（POST /api/v1/system/auth/login，BRIEF-PR3-01 §1.3）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）；
 * 必填约束由 controller @Valid 触发，缺失时由全局渲染器输出 400 ProblemDetail。
 *
 * @param loginName 登录名，非空；来源：用户输入（防枚举口径下不校验存在性差异）
 * @param password  口令明文（仅请求期存在，bcrypt 比对后即弃），非空；敏感字段禁入日志
 */
public record LoginRequest(
        @NotBlank String loginName, @NotBlank String password) {}

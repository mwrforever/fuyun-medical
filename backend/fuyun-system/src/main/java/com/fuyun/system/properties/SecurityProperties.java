package com.fuyun.system.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 认证安全配置属性（fuyun.security.* 前缀，backend 宪法 A.2-2/A.2-4）。
 *
 * <p>record 构造器绑定 + 启动期校验（@Validated），取值非法时应用启动即失败（fail-fast）。
 * HMAC 密钥仅承环境变量 FUYUN_SECURITY_TOKEN_HMAC_SECRET 注入：yml 侧为空占位
 * {@code ${FUYUN_SECURITY_TOKEN_HMAC_SECRET:}}，任何 profile 均不放默认密钥——缺失/短于
 * 32 字符即启动失败，属预期安全姿态（BRIEF-PR3-01 §8-2 红线）。
 *
 * @param tokenHmacSecret 令牌 HMAC-SHA256 签名密钥，非空且 ≥32 字符；来源：FUYUN_SECURITY_TOKEN_HMAC_SECRET
 *                        环境变量（禁明文入 yml/代码/文档/测试断言，测试仅可用注释声明的假密钥）
 * @param accessTokenTtl  access 令牌 TTL，默认 2h，必须为正；滑动续期的会话 TTL 重置基准同取此值
 * @param refreshTokenTtl refresh 令牌 TTL，默认 24h，必须为正；P0 不做 refresh 轮换
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.security")
public record SecurityProperties(
        @NotBlank @Size(min = 32) String tokenHmacSecret,
        @DurationMin(nanos = 1) @DefaultValue("2h") Duration accessTokenTtl,
        @DurationMin(nanos = 1) @DefaultValue("24h") Duration refreshTokenTtl) {}

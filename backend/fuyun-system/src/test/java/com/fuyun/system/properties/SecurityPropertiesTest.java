package com.fuyun.system.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * SecurityProperties 启动期校验测试（BRIEF-PR3-01 §3.1：短密钥/空密钥启动 fail-fast，TTL 正值约束）。
 *
 * <p>经 ApplicationContextRunner 走真实构造器绑定 + @Validated 校验链（非反射直构造），
 * 密钥/TTL 全部经 fuyun.security.* 配置注入——HMAC 密钥仅承环境变量 FUYUN_SECURITY_TOKEN_HMAC_SECRET，
 * 本测试用值为测试资产假密钥，与任何真实凭证无关。
 */
class SecurityPropertiesTest {

    /** 测试资产假密钥（恰好 32 字符下界，仅具单测意义） */
    private static final String TEST_SECRET_32 = "test-hmac-secret-lower-bound-32!";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindConfig.class);

    @Test
    @DisplayName("密钥缺失（空占位）：启动失败且校验消息指向 tokenHmacSecret（fail-fast 契约）")
    void blankSecretFailsStartup() {
        runner.withPropertyValues("fuyun.security.token-hmac-secret=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("tokenHmacSecret");
        });
    }

    @Test
    @DisplayName("密钥短于 32 字符：@Size 下界拒绝，启动失败")
    void shortSecretFailsStartup() {
        runner.withPropertyValues("fuyun.security.token-hmac-secret=short-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("tokenHmacSecret");
                });
    }

    @Test
    @DisplayName("合法密钥：绑定成功且 TTL 取 @DefaultValue（access 2h / refresh 24h）")
    void validSecretBindsWithDefaultTtls() {
        runner.withPropertyValues("fuyun.security.token-hmac-secret=" + TEST_SECRET_32)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.tokenHmacSecret()).isEqualTo(TEST_SECRET_32);
                    assertThat(properties.accessTokenTtl()).isEqualTo(java.time.Duration.ofHours(2));
                    assertThat(properties.refreshTokenTtl()).isEqualTo(java.time.Duration.ofHours(24));
                });
    }

    @Test
    @DisplayName("access TTL 非正值：@DurationMin 拒绝，启动失败")
    void nonPositiveAccessTtlFailsStartup() {
        runner.withPropertyValues(
                        "fuyun.security.token-hmac-secret=" + TEST_SECRET_32, "fuyun.security.access-token-ttl=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("accessTokenTtl");
                });
    }

    @Test
    @DisplayName("refresh TTL 非正值：@DurationMin 拒绝，启动失败")
    void nonPositiveRefreshTtlFailsStartup() {
        runner.withPropertyValues(
                        "fuyun.security.token-hmac-secret=" + TEST_SECRET_32, "fuyun.security.refresh-token-ttl=-1h")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("refreshTokenTtl");
                });
    }

    /** 绑定载体：@Validated 激活 JSR-303 启动期校验（生产经 SystemWebConfig 同型注册，B3.2 装配） */
    @Configuration
    @Validated
    @EnableConfigurationProperties(SecurityProperties.class)
    static class BindConfig {}
}

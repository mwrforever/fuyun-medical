package com.fuyun.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.system.internal.AuthTokenInterceptor;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.service.impl.TokenServiceImpl;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * 系统模块 Web 装配单元测试（401 白名单策略与认证链路 Bean 装配，BRIEF-PR3-01 §1.5）。
 *
 * <p>白名单是免认证攻击面的唯一声明点：仅 login/refresh 可免认证（logout 需令牌），
 * 常量清单经本测试冻结，防"顺手加白"引入未认证面。拦截器注册、令牌服务与 bcrypt
 * 编码器 Bean 的装配可用性一并验证。
 */
class SystemWebConfigTest {

    /** 测试资产假密钥（≥32 字符，仅具单测意义，与任何真实凭证无关） */
    private static final String TEST_SECRET = "unit-test-only-hmac-secret-0123456789abcdef";

    private SystemWebConfig config() {
        return new SystemWebConfig(
                new SecurityProperties(TEST_SECRET, Duration.ofHours(2), Duration.ofHours(24)),
                org.mockito.Mockito.mock(StringRedisTemplate.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("免认证白名单冻结：login/refresh + portal 匿名预约通道（裁决 13），logout 不在白名单（登出需令牌）")
    void authWhitelistContainsOnlyLoginAndRefresh() {
        List<String> whitelist = SystemWebConfig.AUTH_WHITELIST;

        assertThat(whitelist)
                .containsExactlyInAnyOrder(
                        "/api/v1/system/auth/login", "/api/v1/system/auth/refresh", "/api/v1/outpatient/portal/**");
        assertThat(whitelist).noneMatch(path -> path.contains("logout"));
    }

    @Test
    @DisplayName("拦截器注册：/api/v1/** 拦截路径注册 AuthTokenInterceptor 单一拦截器")
    void addInterceptorsRegistersAuthTokenInterceptor() {
        SystemWebConfig config = config();
        ExposingInterceptorRegistry registry = new ExposingInterceptorRegistry();

        config.addInterceptors(registry);

        // 指定路径模式后注册表以 MappedInterceptor 包装拦截器：断言其委托为本模块认证拦截器
        assertThat(registry.registered()).hasSize(1);
        assertThat(registry.registered().get(0)).isInstanceOf(MappedInterceptor.class);
        assertThat(((MappedInterceptor) registry.registered().get(0)).getInterceptor())
                .isInstanceOf(AuthTokenInterceptor.class);
    }

    /** getInterceptors 为 protected：测试子类暴露已注册清单（只读断言用途） */
    private static final class ExposingInterceptorRegistry extends InterceptorRegistry {

        List<Object> registered() {
            return getInterceptors();
        }
    }

    @Test
    @DisplayName("令牌服务 Bean：装配产出 D-2 令牌服务实现（构造期即建，容器单例语义）")
    void tokenServiceBeanIsD2TokenServiceImpl() {
        SystemWebConfig config = config();

        // 装配断言：Bean 方法返回构造器创建的 TokenServiceImpl（认证端点与拦截器共用同一实例）
        assertThat(config.tokenService()).isInstanceOf(TokenServiceImpl.class);
    }

    @Test
    @DisplayName("bcrypt 编码器 Bean：编码→比对往返成立（登录口令校验的算法可用性）")
    void passwordEncoderBeanRoundTripsBcrypt() {
        PasswordEncoder encoder = config().passwordEncoder();

        String hash = encoder.encode("Fuyun@2026");
        assertThat(hash).startsWith("$2a$");
        assertThat(encoder.matches("Fuyun@2026", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }
}

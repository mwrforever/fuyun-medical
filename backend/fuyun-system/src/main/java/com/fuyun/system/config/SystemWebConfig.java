package com.fuyun.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.system.controller.AuthController;
import com.fuyun.system.controller.DictController;
import com.fuyun.system.controller.DictTypeController;
import com.fuyun.system.controller.DictVersionController;
import com.fuyun.system.convert.AuthConverter;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.internal.AuthTokenInterceptor;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.service.ITokenService;
import com.fuyun.system.service.impl.AuthServiceImpl;
import com.fuyun.system.service.impl.DictItemServiceImpl;
import com.fuyun.system.service.impl.DictQueryServiceImpl;
import com.fuyun.system.service.impl.DictTypeServiceImpl;
import com.fuyun.system.service.impl.DictVersionServiceImpl;
import com.fuyun.system.service.impl.RoleServiceImpl;
import com.fuyun.system.service.impl.TokenServiceImpl;
import com.fuyun.system.service.impl.UserServiceImpl;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 系统模块 Web 装配（BRIEF-PR3-01 §1.5/§3.2）：认证拦截器注册（401 白名单策略）+
 * 认证与字典域链路 Bean 装配集中点。
 *
 * <p>com.fuyun.system 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，
 * 本类经 fuyun-app SystemConfig @Import 生效（PR #4 既有裁决：装配归 app，不放宽扫描）；
 * 模块内服务/控制器经 {@code @Import} 显式注册为 Bean（MapStruct 接口经 @Bean 装配生成实现）。
 *
 * <p>401 白名单策略：拦截路径 {@code /api/v1/**}，仅 login/refresh 免认证（logout 需令牌，
 * 属"需认证"端点）；actuator/springdoc 路径不在 {@code /api/v1/**} 下，天然不受拦截，
 * 无需额外白名单（简报 §1.5 口径）。
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
@Import({
    UserServiceImpl.class,
    RoleServiceImpl.class,
    AuthServiceImpl.class,
    AuthController.class,
    DictTypeServiceImpl.class,
    DictVersionServiceImpl.class,
    DictItemServiceImpl.class,
    DictQueryServiceImpl.class,
    DictTypeController.class,
    DictVersionController.class,
    DictController.class
})
public class SystemWebConfig implements WebMvcConfigurer {

    /**
     * 免认证白名单：仅登录与刷新两端点（常量收口防散落，供装配与测试断言共用）。
     *
     * <p>注意 logout 不在白名单：登出请求本身需通过 401 认证（防止伪造/无效令牌触发会话删除探测）。
     */
    public static final List<String> AUTH_WHITELIST =
            List.of("/api/v1/system/auth/login", "/api/v1/system/auth/refresh");

    /** 认证拦截拦截路径：全部业务 API（含未来模块，P0 只做认证 401 不做 403 鉴权） */
    private static final String INTERCEPT_PATH_PATTERN = "/api/v1/**";

    /** Boot 全局定制 ObjectMapper（401 ProblemDetail 手工序列化与全局渲染同源） */
    private final ObjectMapper objectMapper;

    /** 令牌服务实例：构造期一次性创建，经 {@link #tokenService()} 以 Bean 暴露为容器单例 */
    private final ITokenService tokenService;

    /**
     * 全参构造器：依赖全部为外部 Bean（无本类 @Bean 产物，无装配环），令牌服务在此即建。
     *
     * @param properties   安全配置，非空；来源：fuyun.security.* 经 @EnableConfigurationProperties 注册
     * @param redisTemplate String 序列化 Redis 模板，非空；来源：Boot 自动装配
     * @param objectMapper JSON 转换器，非空；来源：Boot 全局定制实例（Long→String 定制生效）
     */
    public SystemWebConfig(
            SecurityProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 生产时间源：Clock.systemUTC()（TokenServiceImpl 以 Clock 注入保证可测性，单测另建实例注入固定时钟）
        this.tokenService = new TokenServiceImpl(properties, redisTemplate, objectMapper, Clock.systemUTC());
    }

    /**
     * 令牌服务 Bean：HMAC 签发/校验 + Redis 会话承载（D-2 核心构件，AuthService 与拦截器共用单例）。
     *
     * @return 令牌服务实例
     */
    @Bean
    public ITokenService tokenService() {
        return tokenService;
    }

    /**
     * 口令编码器 Bean：bcrypt（$2a$，宪法 A.4.2-10 点名单 jar 构件，非 spring-security 全家桶）。
     *
     * @return bcrypt 口令编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证域 MapStruct 转换器 Bean：接口不可经 @Import 注册，经 Mappers.getMapper 装配生成实现
     * （与单测取用同源，AuthConverter.INSTANCE）。
     *
     * @return 认证域转换器
     */
    @Bean
    public AuthConverter authConverter() {
        return AuthConverter.INSTANCE;
    }

    /**
     * 字典域 MapStruct 转换器 Bean：接口不可经 @Import 注册，经 Mappers.getMapper 装配生成实现。
     *
     * @return 字典域转换器
     */
    @Bean
    public DictConverter dictConverter() {
        return DictConverter.INSTANCE;
    }

    /**
     * 认证拦截器注册：拦截 /api/v1/**，白名单仅 login/refresh。
     *
     * <p>执行顺序：TraceIdFilter（HIGHEST_PRECEDENCE）先于本拦截器建立 MDC traceId，
     * 401 body 与日志均可携带（BRIEF-PR3-01 §1.4 前提）。
     *
     * @param registry MVC 拦截器注册器，非空
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthTokenInterceptor(tokenService, objectMapper))
                .addPathPatterns(INTERCEPT_PATH_PATTERN)
                .excludePathPatterns(AUTH_WHITELIST);
    }
}

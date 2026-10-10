package com.fuyun.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.system.api.TokenVerifier;
import com.fuyun.system.controller.AuthController;
import com.fuyun.system.controller.DictController;
import com.fuyun.system.controller.DictTypeController;
import com.fuyun.system.controller.DictVersionController;
import com.fuyun.system.controller.OrgController;
import com.fuyun.system.controller.PermissionAdminController;
import com.fuyun.system.controller.PracticeController;
import com.fuyun.system.controller.PracticeGrantController;
import com.fuyun.system.controller.RoleAdminController;
import com.fuyun.system.convert.AuthConverter;
import com.fuyun.system.convert.DictConverter;
import com.fuyun.system.internal.AuditLogAspect;
import com.fuyun.system.internal.AuthTokenInterceptor;
import com.fuyun.system.internal.AuthorizationInterceptor;
import com.fuyun.system.internal.PermissionRegistry;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.service.ITokenService;
import com.fuyun.system.service.impl.AuditLogServiceImpl;
import com.fuyun.system.service.impl.AuthServiceImpl;
import com.fuyun.system.service.impl.DictItemServiceImpl;
import com.fuyun.system.service.impl.DictQueryServiceImpl;
import com.fuyun.system.service.impl.DictTypeServiceImpl;
import com.fuyun.system.service.impl.DictVersionServiceImpl;
import com.fuyun.system.service.impl.OrgQueryServiceImpl;
import com.fuyun.system.service.impl.PermissionAdminServiceImpl;
import com.fuyun.system.service.impl.PracticeCheckPortImpl;
import com.fuyun.system.service.impl.PracticeServiceImpl;
import com.fuyun.system.service.impl.RoleAdminServiceImpl;
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
 * 认证与字典域链路 Bean 装配集中点（Task 8 追加：PracticeCheckPort 跨模块 api 面实现——
 * M03 开单执业授权强校验进程内消费通道）；PR-4D 追加 403 鉴权面（PermissionRegistry
 * 启动装载 + AuthorizationInterceptor 第二道拦截器，W-37 主体构件）；PR-4F 追加权限
 * 管理台读链路两服务两端点（RoleAdminServiceImpl/PermissionAdminServiceImpl 与
 * RoleAdminController/PermissionAdminController，Task 4）。
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
    DictController.class,
    OrgQueryServiceImpl.class,
    OrgController.class,
    AuditLogServiceImpl.class,
    AuditLogAspect.class,
    PracticeServiceImpl.class,
    PracticeCheckPortImpl.class,
    PracticeController.class,
    PracticeGrantController.class,
    RoleAdminServiceImpl.class,
    RoleAdminController.class,
    PermissionAdminServiceImpl.class,
    PermissionAdminController.class
})
public class SystemWebConfig implements WebMvcConfigurer {

    /**
     * 免认证白名单：登录/刷新两端点 + portal 患者匿名预约通道（裁决 13：/api/v1/outpatient/portal/**
     * 免 401，服务端经介质解析定 patientId、操作者留痕取哨兵 PORTAL；限流/风控随 M18 注记）
     * + bigscreen 候诊榜只读快照（UI 设计文档 §8.5「REST 快照首屏、路由 query 书签化」的
     * 无登录态设备直开场景，bigscreen http.ts 匿名只读面口径——大屏无 Authorization 注入；
     * 端点自身脱敏出网（patientName 掩码、无证件号字段），且该路径仅映射只读 GET，动作类
     * POST 在 /queue/... 单数路径不受放行影响）
     * + bigscreen 订阅令牌签发（BUG-19：大屏 WS 链路凭证改运行期获取的匿名入口，5 分钟短期
     * 单 access 令牌 + 哨兵零角色会话——替代构建期 VITE_ 内联红线缺陷，P2 演进注记见
     * IAuthService#issueBigscreenToken）。常量收口防散落，供装配与测试断言共用。
     *
     * <p>注意 logout 不在白名单：登出请求本身需通过 401 认证（防止伪造/无效令牌触发会话删除探测）。
     */
    public static final List<String> AUTH_WHITELIST = List.of(
            "/api/v1/system/auth/login",
            "/api/v1/system/auth/refresh",
            "/api/v1/system/auth/bigscreen-token",
            "/api/v1/outpatient/portal/**",
            "/api/v1/outpatient/queues/*/tickets");

    /**
     * 哨兵令牌 REST 只读 allowlist（W-39 限行面）：前两条按前缀匹配+尾段 wardId 一致性，
     * 第三条精确+query 一致性（消费方 AuthTokenInterceptor 跨包引用，故与 AUTH_WHITELIST
     * 同为 public；任何新增端点即扩大匿名令牌暴露面，变更须经安全评审）。
     */
    public static final List<String> SENTINEL_ALLOWLIST =
            List.of("/api/v1/nursing/board/", "/api/v1/ward/infusion-board/", "/api/v1/iot/alarms");

    /** 认证拦截拦截路径：全部业务 API（401 认证与 403 鉴权两道拦截器共用同一路径面） */
    private static final String INTERCEPT_PATH_PATTERN = "/api/v1/**";

    /** Boot 全局定制 ObjectMapper（401/403 ProblemDetail 手工序列化与全局渲染同源） */
    private final ObjectMapper objectMapper;

    /** 令牌服务实例：构造期一次性创建，经 {@link #tokenService()}/{@link #tokenVerifier()} 双接口以 Bean 暴露为容器单例 */
    private final TokenServiceImpl tokenService;

    /** 权限点数据访问：403 鉴权矩阵装载源（sys_permission） */
    private final PermissionMapper permissionMapper;

    /** 角色-权限绑定数据访问：403 鉴权矩阵装载源（sys_role_permission） */
    private final RolePermissionMapper rolePermissionMapper;

    /** 角色数据访问：403 鉴权矩阵装载源（sys_role 取 ACTIVE 角色码） */
    private final RoleMapper roleMapper;

    /**
     * 全参构造器：依赖全部为外部 Bean（无本类 @Bean 产物，无装配环），令牌服务在此即建。
     *
     * @param properties           安全配置，非空；来源：fuyun.security.* 经 @EnableConfigurationProperties 注册
     * @param redisTemplate        String 序列化 Redis 模板，非空；来源：Boot 自动装配
     * @param objectMapper         JSON 转换器，非空；来源：Boot 全局定制实例（Long→String 定制生效）
     * @param permissionMapper     权限点 mapper，非空；来源：app 侧 @MapperScan 注册（PR-4D 403 矩阵装载源）
     * @param rolePermissionMapper 绑定关系 mapper，非空；来源：同上
     * @param roleMapper           角色 mapper，非空；来源：同上
     */
    public SystemWebConfig(
            SecurityProperties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            PermissionMapper permissionMapper,
            RolePermissionMapper rolePermissionMapper,
            RoleMapper roleMapper) {
        this.objectMapper = objectMapper;
        // 生产时间源：Clock.systemUTC()（TokenServiceImpl 以 Clock 注入保证可测性，单测另建实例注入固定时钟）
        this.tokenService = new TokenServiceImpl(properties, redisTemplate, objectMapper, Clock.systemUTC());
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleMapper = roleMapper;
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
     * 访问令牌布尔校验 Bean（PR-4 B4.3 跨模块小改）：与令牌服务同一实例，以 {@link TokenVerifier}
     * 接口类型单独暴露，供 iot STOMP CONNECT 帧鉴权等跨模块消费方按最小契约注入（B.2-2 只依赖
     * api 包，不感知 ITokenService 完整签发/刷新/登出面）。
     *
     * @return 访问令牌布尔校验实例（与 {@link #tokenService()} 同一单例，非新建）
     */
    @Bean
    public TokenVerifier tokenVerifier() {
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
     * 403 鉴权矩阵登记面 Bean（PR-4D W-37 主体）：@Bean 方法内构造后显式装载一次——
     * 权限数据变更须重启生效，运行期动态刷新归 PR-4F（Redis pub/sub 演进注记）；
     * load() 幂等可重载的语义为未来刷新通道预留。
     *
     * @return 已完成启动装载的权限点登记面单例
     */
    @Bean
    public PermissionRegistry permissionRegistry() {
        PermissionRegistry registry = new PermissionRegistry(permissionMapper, rolePermissionMapper, roleMapper);
        registry.load();
        return registry;
    }

    /**
     * 双拦截器注册（PR-4D 起 401 认证 + 403 鉴权两道闸，同路径面同白名单）。
     *
     * <p>执行顺序：TraceIdFilter（HIGHEST_PRECEDENCE）→ AuthTokenInterceptor（401 认证并注入
     * 角色/操作人上下文）→ AuthorizationInterceptor（403 矩阵判定，读上下文只读）。哨兵三端点
     * （board/infusion-board/iot-alarms）不额外 exclude——其 D5 豁免靠"不进 API 矩阵+空角色
     * 会话在已登记路径必拒"的挂码语义承载，非白名单放行。
     *
     * @param registry MVC 拦截器注册器，非空
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthTokenInterceptor(tokenService, objectMapper))
                .addPathPatterns(INTERCEPT_PATH_PATTERN)
                .excludePathPatterns(AUTH_WHITELIST);
        registry.addInterceptor(new AuthorizationInterceptor(permissionRegistry(), objectMapper))
                .addPathPatterns(INTERCEPT_PATH_PATTERN)
                .excludePathPatterns(AUTH_WHITELIST);
    }
}

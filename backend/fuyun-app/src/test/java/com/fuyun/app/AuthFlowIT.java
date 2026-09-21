package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 登录链路端到端集成测试（B3.3 交付，BRIEF-PR3-01 §5.1 七步断言）：真实穿过
 * TraceIdFilter → AuthTokenInterceptor → Controller → Service 全链（RANDOM_PORT +
 * TestRestTemplate，MockMvc 不含 Filter 链不作首选）。
 *
 * <p>业务意图：验证 D-2 认证链路的完整用户旅程——错误口令防枚举（SYS-1001）、种子账号登录、
 * 无令牌 401（SYS-1003）与 traceId 双通道一致、携带令牌访问受保护端点、refresh 换发与登出后
 * 会话删除（旧令牌失效）、连续失败触发锁定（SYS-1002）、审计切面落库断言（LOGIN 行含
 * traceId 与脱敏后的 fail_reason/detail，B3.3 审计链路的端到端留证载体）、大屏候诊榜快照
 * 匿名可达而动作端点仍 401（步骤8，Task 15 Step6 D-2 白名单只读放行的面界断言）。
 *
 * <p>容器三件套与 {@link SmokeStackIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）；HMAC 密钥经 @DynamicPropertySource 注入测试资产
 * 假密钥（非真实凭证，真实密钥只经环境变量注入）。审计落库经 JdbcTemplate 查 system.audit_log 断言。
 *
 * <p>八步断言按序执行（@Order 串联，登录状态与失败计数跨步累积属业务链路语义）；令牌经静态
 * 持有器跨用例传递（JUnit 默认每方法新实例，静态字段承载链路状态，同 MessagingGovernanceIT 姿态）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIT {

    /** TimescaleDB 容器：审计落库（system.audit_log）与 RBAC 种子的断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：D-2 会话键（fy:system:session:{sid}）的真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：完整上下文装配必需（消费容器随上下文启动），本类不断言消息链路 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-auth.conf");

    /** 测试资产假密钥（57 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /**
     * 注入测试用 HMAC 密钥：SecurityProperties（fuyun.security.*）密钥缺失即启动 fail-fast。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 种子账号登录名（V303 种子 admin，M01 Spec §5 内置超管） */
    private static final String ADMIN_LOGIN_NAME = "admin";

    /** 错误口令样本：审计脱敏断言的对照明文（不得出现在 fail_reason/detail 中） */
    private static final String WRONG_PASSWORD = "WrongPass#2026";

    /** 登录成功用例注入的追踪锚点：审计行 trace_id 与响应头 X-Trace-Id 的一致性断言来源 */
    private static final String LOGIN_TRACE_ANCHOR = "it-authflow-login-success-trace";

    /** 免令牌受保护端点用例注入的追踪锚点：401 body.traceId 与响应头一致性断言来源 */
    private static final String PROTECTED_TRACE_ANCHOR = "it-authflow-protected-trace";

    /** 受保护骨架端点（B3.3 交付）：无令牌 401 / 携带令牌 200 的验证目标 */
    private static final String PRACTICE_CHECK_URI = "/api/v1/system/practice/check";

    /** step2 登录成功的双令牌持有器：静态承载跨用例链路状态（access + refresh） */
    static final AtomicReference<String> ACCESS_TOKEN = new AtomicReference<>();

    static final AtomicReference<String> REFRESH_TOKEN = new AtomicReference<>();

    /** step5 登出所用令牌持有器：审计落库脱敏断言的对照明文（C-1 防回归，不得落入 audit_log.detail） */
    static final AtomicReference<String> LOGOUT_TOKEN = new AtomicReference<>();

    /** HTTP 客户端：真实穿过 Filter→Interceptor→Controller 全链 */
    private final TestRestTemplate restTemplate;

    /** JSON 解析器：响应体 ProblemDetail / 业务载荷断言 */
    private final ObjectMapper objectMapper;

    /** JDBC 模板：system.audit_log 审计落库断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入：Spring 6.2 测试构造器默认按注解识别（annotated 模式），须显式标注 @Autowired
     * 方可让 SpringExtension 从上下文解析各依赖；非空，来源为 fuyun-app test 上下文自动装配。
     *
     * @param restTemplate TestRestTemplate，RANDOM_PORT 全链 HTTP 客户端
     * @param objectMapper 全局定制 ObjectMapper，响应体 JSON 解析
     * @param jdbcTemplate JDBC 模板，审计表断言
     */
    @Autowired
    AuthFlowIT(TestRestTemplate restTemplate, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @Order(1)
    @DisplayName("步骤1：错误口令登录 401 SYS-1001 防枚举文案，ProblemDetail 携带 traceId")
    void wrongPasswordLoginIsRejectedWithAntiEnumerationDetail() throws Exception {
        ResponseEntity<String> response =
                postJson("/api/v1/system/auth/login", loginBody(ADMIN_LOGIN_NAME, WRONG_PASSWORD), null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("errorCode").asText()).isEqualTo("SYS-1001");
        // 防枚举红线：detail 不含"账号不存在"类差异文案，也不回显口令
        assertThat(body.path("detail").asText()).isEqualTo("登录名或密码错误");
        assertThat(body.path("traceId").asText()).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("步骤2：admin 正确登录 200，双令牌与 user.userId 均为 JSON 字符串，角色摘要含 ADMIN")
    void adminLoginReturnsTokenPairWithStringIdentifiers() throws Exception {
        ResponseEntity<String> response =
                postJson("/api/v1/system/auth/login", loginBody(ADMIN_LOGIN_NAME, "Fuyun@2026"), LOGIN_TRACE_ANCHOR);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.path("accessToken").isTextual())
                .as("accessToken 须为 JSON 字符串")
                .isTrue();
        assertThat(body.path("refreshToken").isTextual())
                .as("refreshToken 须为 JSON 字符串")
                .isTrue();
        assertThat(body.path("user").path("userId").isTextual())
                .as("user.userId 经 Long→String 定制须为 JSON 字符串")
                .isTrue();
        assertThat(body.path("user").path("loginName").asText()).isEqualTo(ADMIN_LOGIN_NAME);
        assertThat(body.path("user").path("roles").toString()).contains("ADMIN");
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        ACCESS_TOKEN.set(body.path("accessToken").asText());
        REFRESH_TOKEN.set(body.path("refreshToken").asText());
    }

    @Test
    @Order(3)
    @DisplayName("步骤3：无令牌访问受保护端点 401 SYS-1003，body.traceId 与响应头 X-Trace-Id 一致")
    void protectedEndpointWithoutTokenIsRejectedWithConsistentTraceId() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                PRACTICE_CHECK_URI, HttpMethod.POST, protectedEndpointEntity(null, "{}"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("errorCode").asText()).isEqualTo("SYS-1003");
        // traceId 双通道一致：请求头注入的锚点 → MDC → 401 body 与响应头同源回写
        assertThat(body.path("traceId").asText()).isEqualTo(PROTECTED_TRACE_ANCHOR);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(PROTECTED_TRACE_ANCHOR);
    }

    @Test
    @Order(4)
    @DisplayName("步骤4：携带令牌访问受保护端点 200，返回 P0 骨架响应 passed=false")
    void protectedEndpointWithTokenReturnsSkeletonResponse() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                PRACTICE_CHECK_URI,
                HttpMethod.POST,
                protectedEndpointEntity(ACCESS_TOKEN.get(), practiceCheckBody()),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("passed").asBoolean()).isFalse();
        assertThat(body.path("reason").asText()).isNotBlank();
        assertThat(body.path("employeeId").isTextual())
                .as("employeeId 经 Long→String 须为 JSON 字符串")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("步骤5：refresh 换发新 accessToken 可用；logout 后原 accessToken 再访问 401（会话已删）")
    void refreshExchangesNewTokenAndLogoutInvalidatesOldToken() throws Exception {
        // refresh：typ=refresh 令牌换发同 sid 新 access（refresh 值不轮换）
        ResponseEntity<String> refreshResponse = postJson(
                "/api/v1/system/auth/refresh",
                objectMapper.writeValueAsString(
                        objectMapper.createObjectNode().put("refreshToken", REFRESH_TOKEN.get())),
                null);
        assertThat(refreshResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode refreshed = objectMapper.readTree(refreshResponse.getBody());
        String newAccessToken = refreshed.path("accessToken").asText();
        assertThat(newAccessToken).isNotBlank();

        // 新 accessToken 立即可用于受保护端点（校验链 + 滑动续期全链生效）
        ResponseEntity<String> withNewToken = restTemplate.exchange(
                PRACTICE_CHECK_URI,
                HttpMethod.POST,
                protectedEndpointEntity(newAccessToken, practiceCheckBody()),
                String.class);
        assertThat(withNewToken.getStatusCode().value()).isEqualTo(200);

        // 登出：删会话键，access 与 refresh 同 sid 同时失效（登记对照明文供步骤7 审计脱敏断言）
        LOGOUT_TOKEN.set(newAccessToken);
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/v1/system/auth/logout",
                HttpMethod.POST,
                protectedEndpointEntity(newAccessToken, "{}"),
                String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);

        // 登出后原 accessToken 再访问：会话不存在 → 401 SYS-1003
        ResponseEntity<String> afterLogout = restTemplate.exchange(
                PRACTICE_CHECK_URI,
                HttpMethod.POST,
                protectedEndpointEntity(newAccessToken, practiceCheckBody()),
                String.class);
        assertThat(afterLogout.getStatusCode().value()).isEqualTo(401);
        assertThat(objectMapper
                        .readTree(afterLogout.getBody())
                        .path("errorCode")
                        .asText())
                .isEqualTo("SYS-1003");
    }

    @Test
    @Order(6)
    @DisplayName("步骤6：连续 5 次错密码（阈值触发次仍防枚举文案）→ 第 6 次 401 SYS-1002 文案含解锁时间")
    void consecutiveFailuresTriggerAccountLock() throws Exception {
        // 失败计数自步骤2 成功登录复位后起算：连续 5 次失败，第 5 次达阈值置 locked_until（响应仍防枚举文案）
        List<String> errorCodes = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<String> response =
                    postJson("/api/v1/system/auth/login", loginBody(ADMIN_LOGIN_NAME, WRONG_PASSWORD), null);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            errorCodes.add(
                    objectMapper.readTree(response.getBody()).path("errorCode").asText());
        }
        assertThat(errorCodes).as("阈值触发前/当次均按防枚举文案拒绝").containsOnly("SYS-1001");

        // 第 6 次请求：锁定态拒绝（SYS-1002），文案须含解锁时间（自动到期恢复口径）
        ResponseEntity<String> lockedResponse =
                postJson("/api/v1/system/auth/login", loginBody(ADMIN_LOGIN_NAME, WRONG_PASSWORD), null);
        assertThat(lockedResponse.getStatusCode().value()).isEqualTo(401);
        JsonNode locked = objectMapper.readTree(lockedResponse.getBody());
        assertThat(locked.path("errorCode").asText()).isEqualTo("SYS-1002");
        assertThat(locked.path("detail").asText()).contains("后重试");
    }

    @Test
    @Order(7)
    @DisplayName("步骤7：审计落库断言——登录 SUCCESS 行含 operator/traceId，失败行 fail_reason 已脱敏不含口令")
    void auditLogRowsAreWrittenWithMaskedSensitiveFields() {
        // 成功登录审计行：action_type=LOGIN、operator=admin（免认证端点回退取登录名）、traceId=注入锚点、result=SUCCESS
        List<String> traceIds = jdbcTemplate.queryForList(
                "SELECT trace_id FROM system.audit_log"
                        + " WHERE action_type = 'LOGIN' AND operator_id = ? AND result = 'SUCCESS'",
                String.class,
                ADMIN_LOGIN_NAME);
        assertThat(traceIds).as("成功登录必须留下审计行").contains(LOGIN_TRACE_ANCHOR);

        // 失败登录审计行：result=FAIL 且 fail_reason/detail 均不含错误口令明文（脱敏红线）
        List<java.util.Map<String, Object>> failRows = jdbcTemplate.queryForList(
                "SELECT fail_reason, detail FROM system.audit_log"
                        + " WHERE action_type = 'LOGIN' AND result = 'FAIL' AND operator_id = ?",
                ADMIN_LOGIN_NAME);
        assertThat(failRows).as("失败登录必须留下审计行").isNotEmpty();
        assertThat(failRows).allSatisfy(row -> {
            assertThat((String) row.get("fail_reason")).doesNotContain(WRONG_PASSWORD);
            assertThat((String) row.get("detail")).doesNotContain(WRONG_PASSWORD);
        });

        // logout 审计行（C-1 防回归）：Authorization 头原文经打码，令牌原文禁入审计 detail
        List<String> logoutDetails = jdbcTemplate.queryForList(
                "SELECT detail FROM system.audit_log WHERE action_type = 'LOGIN' AND resource = ?",
                String.class,
                "/api/v1/system/auth/logout");
        assertThat(logoutDetails).as("logout 必须留下审计行").isNotEmpty();
        assertThat(logoutDetails)
                .allSatisfy(detail -> assertThat(detail).isNotBlank().doesNotContain(LOGOUT_TOKEN.get()));
    }

    @Test
    @Order(8)
    @DisplayName("步骤8：大屏候诊榜快照匿名可达 200（白名单只读面）；动作端点匿名仍 401（面未扩大）")
    void bigscreenQueueSnapshotIsAnonymouslyReachable() throws Exception {
        // 匿名 GET 快照（无 Authorization 头——bigscreen http.ts 匿名只读面口径）：白名单放行 200，
        // 响应体为票据数组（本类上下文队列为空，仅断言形态不绑内容）
        ResponseEntity<String> snapshot = restTemplate.exchange(
                "/api/v1/outpatient/queues/IT-AUTHFLOW-DEPT/tickets", HttpMethod.GET, null, String.class);
        assertThat(snapshot.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(snapshot.getBody()).isArray())
                .as("快照响应体为票据出参数组")
                .isTrue();

        // 反向断言（Task 15 Step6 D-2 防面扩大）：同前缀动作端点不在白名单，匿名仍 401
        ResponseEntity<String> callAction = postJson("/api/v1/outpatient/queue/call", "{}", null);
        assertThat(callAction.getStatusCode().value()).isEqualTo(401);
    }

    /**
     * 发送 JSON POST 并返回原始响应（显式注入 X-Trace-Id 请求头供审计/401 契约断言）。
     *
     * @param uri           目标 URI，非空
     * @param jsonBody      请求体 JSON 字符串，非空
     * @param traceIdAnchor 追踪锚点，可空；null 不注入请求头（由 TraceIdFilter 兜底生成）
     * @return 原始 HTTP 响应（状态码与响应头可断言），非空
     */
    private ResponseEntity<String> postJson(String uri, String jsonBody, String traceIdAnchor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (traceIdAnchor != null) {
            headers.set("X-Trace-Id", traceIdAnchor);
        }
        return restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /**
     * 构造登录请求体 JSON。
     *
     * @param loginName 登录名，非空
     * @param password  口令明文，非空（仅测试请求期存在，断言其不得落入审计表）
     * @return 登录请求 JSON 字符串
     */
    private String loginBody(String loginName, String password) throws Exception {
        return objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("loginName", loginName).put("password", password));
    }

    /**
     * 构造受保护端点 POST 实体：固定注入 X-Trace-Id 锚点请求头（traceId 双通道断言来源），
     * 携带可选 Bearer 令牌。
     *
     * @param token    Bearer 令牌，可空；null 不携带 Authorization 头（无令牌场景）
     * @param jsonBody 请求体 JSON 字符串，非空（401 场景请求体不参与断言，传 "{}" 即可）
     * @return HTTP 实体，非空
     */
    private HttpEntity<String> protectedEndpointEntity(String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", PROTECTED_TRACE_ANCHOR);
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return new HttpEntity<>(jsonBody, headers);
    }

    /**
     * 构造 practice/check 合法请求体 JSON（employeeId/grantType 过 JSR-303 非空校验）。
     *
     * @return 校验请求 JSON 字符串
     */
    private String practiceCheckBody() throws Exception {
        return objectMapper
                .createObjectNode()
                .put("employeeId", 123)
                .put("grantType", "医师执业范围")
                .toString();
    }
}

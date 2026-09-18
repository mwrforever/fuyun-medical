package com.fuyun.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * fuyun-app IT 共享夹具基类（终审 Minor「测试夹具收敛」，PR-3 提炼；第 2 轮审查 P1-1 主控裁决
 * =只收敛「无状态重复件」，容器不收敛）：测试假密钥三元组 @DynamicPropertySource（token-hmac
 * 与 crypto 两 key 均与容器无关）+ 真实登录取令牌 + 第二审批账号播种助手 + 共享常量。
 *
 * <p>三 @Container 容器禁收敛入基类之理由：static 容器字段随继承即全子类共享——所有 IT 连同一套
 * broker/DB，Empi 手工捕获队列 q.patient.patient.* 会与其他 IT 存活上下文里活的
 * PatientCacheInvalidationListener 抢消费，跨测试串扰（flaky 之源）；容器是测试隔离语义必需件，
 * 各 IT 类级各自声明、每 IT 独占一套（仓内 MessagingGovernanceIT/SmokeStackIT 各自声明容器
 * 既有惯例同源），@ServiceConnection 亦由各 IT 自行标注，基类零参与。
 *
 * <p>抽象类不匹配 failsafe 的 {@code *IT.java} 后缀拾取约定（宪法 C.4「编排基类用非拾取命名」）；
 * @Testcontainers/@ActiveProfiles 类注解留基类（JUnit5 @ExtendWith 继承语义对子类生效），
 * 子类继续声明 @SpringBootTest/@TestMethodOrder、业务 @TestConfiguration 与自有容器三件套。
 */
@Testcontainers
@ActiveProfiles("test")
abstract class FuyunStackITBase {

    /** 测试假密钥三件套（仅具 IT 意义，非生产凭据） */
    protected static final String TEST_SECRET = "it-only-fake-secret-0123456789abcdef0123456789abcdef";

    protected static final String TEST_DATA_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    protected static final String TEST_MAC_KEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    /** V303 种子超管与口令 */
    protected static final String ADMIN_LOGIN_NAME = "admin";

    protected static final String SEED_PASSWORD = "Fuyun@2026";

    /** 双人角色第二账号（IT 播种，小整数种子 ID 口径） */
    protected static final String REVIEWER_LOGIN_NAME = "it-reviewer";

    protected static final long REVIEWER_USER_ID = 2L;

    /** 动态属性注入：token HMAC 与患者域加密构件启动 fail-fast 前置 */
    @DynamicPropertySource
    static void registerSecrets(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_SECRET);
        registry.add("fuyun.patient.crypto.data-key", () -> TEST_DATA_KEY);
        registry.add("fuyun.patient.crypto.mac-key", () -> TEST_MAC_KEY);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 真实登录取 Bearer 令牌（HTTP 全链，不绕鉴权）。
     *
     * @param loginName 登录名；口令恒为种子口令
     * @return 令牌字符串，非空
     * @throws IllegalStateException 登录失败（响应缺 accessToken）
     */
    protected String loginToken(String loginName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode resp = postJson(
                "/api/v1/system/auth/login",
                headers,
                objectMapper.createObjectNode().put("loginName", loginName).put("password", SEED_PASSWORD));
        String token = resp.path("accessToken").asText(null);
        if (token == null) {
            throw new IllegalStateException("IT 登录失败：loginName=" + loginName + "，resp=" + resp);
        }
        return token;
    }

    /**
     * 按 V303 形态播种第二审批账号（口令哈希直取 admin 行，禁明文/禁硬编码哈希；复用 ADMIN 角色
     * 绑定，双人角色语义成立）。播种 SQL 以 EmpiGovernanceIT.ensurePrincipals 实况段为权威整段
     * 对齐重写（第 2 轮审查 P2-8）：两段式 sys_user + sys_user_role，无 sys_employee 行——Empi
     * 实况从不插员工表，双人守卫仅依赖 user+role 绑定。
     *
     * @return 播种账号 id
     */
    protected long seedReviewerUser() {
        String adminHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM system.sys_user WHERE login_name = ?", String.class, ADMIN_LOGIN_NAME);
        // 幂等：重跑 IT（同一容器生命周期内仅一次，容器各 IT 独占天然干净；条件插入兜底同 JVM 复用）
        jdbcTemplate.update(
                "INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)"
                        + " SELECT ?, ?, ?, 'STAFF', 'ACTIVE'"
                        + " WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = ?)",
                REVIEWER_USER_ID,
                REVIEWER_LOGIN_NAME,
                adminHash,
                REVIEWER_LOGIN_NAME);
        jdbcTemplate.update(
                "INSERT INTO system.sys_user_role (id, user_id, role_id)"
                        + " SELECT ?, ?, 1"
                        + " WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = ? AND role_id = 1)",
                REVIEWER_USER_ID,
                REVIEWER_USER_ID,
                REVIEWER_USER_ID);
        return REVIEWER_USER_ID;
    }

    /**
     * POST JSON 助手（headers 承载 Content-Type/Bearer，可空=匿名）。响应体经 readTree 解析为
     * JsonNode（对齐 EmpiGovernanceIT.loginAndGetAccessToken 口径——valueToTree(String) 会把整段
     * JSON 包成文本节点，path("accessToken") 恒取空导致登录助手静默失效）。
     *
     * @param path    目标 URI（/api/v1 前缀），非空
     * @param headers 请求头，非空（可仅含 Content-Type）
     * @param body    请求体对象（ObjectNode/record），可空=无体
     * @return 响应体 JSON 节点，非空
     * @throws IllegalStateException 响应体非合法 JSON（契约断裂显式失败，禁静默吞解析异常）
     */
    protected JsonNode postJson(String path, HttpHeaders headers, Object body) {
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        String respBody = restTemplate.postForEntity(path, entity, String.class).getBody();
        try {
            return objectMapper.readTree(respBody);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

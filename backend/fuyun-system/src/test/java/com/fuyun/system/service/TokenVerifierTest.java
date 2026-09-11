package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenClaims;
import com.fuyun.system.record.TokenPair;
import com.fuyun.system.service.impl.TokenServiceImpl;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * TokenVerifier 布尔校验契约测试（PR-4 B4.3 跨模块小改，BRIEF-PR4-01 §4）。
 *
 * <p>覆盖：有效 access 令牌 true；篡改签名 / 过期 / typ 不符（refresh 当 access 用）/
 * 会话已删（登出后）均 false；null 与空白令牌 false（握手头缺失边界）。契约核心 =
 * 任何失败收敛 false 且不抛异常（WebSocket 握手无 ProblemDetail 出口，布尔语义足够）。
 * Redis 以 Mockito 桩承载（真实 Redis 交互由端到端 IT 把关）。
 */
@ExtendWith(MockitoExtension.class)
class TokenVerifierTest {

    /** 测试资产假密钥（≥32 字符，仅具单测意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_SECRET = "unit-test-only-hmac-secret-0123456789abcdef";

    /** 固定当前时刻：过期令牌构造的确定性基准 */
    private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");

    /** access TTL 测试值 */
    private static final Duration ACCESS_TTL = Duration.ofHours(2);

    /** refresh TTL 测试值 */
    private static final Duration REFRESH_TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        // lenient 桩：过期/篡改/typ 错等失败路径不触达 valueOps，宽松化避免 UnnecessaryStubbing 误报
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        SecurityProperties properties = new SecurityProperties(TEST_SECRET, ACCESS_TTL, REFRESH_TTL);
        tokenService =
                new TokenServiceImpl(properties, redisTemplate, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("有效 access 令牌校验通过返回 true（签发→布尔校验全链往返）")
    void validAccessTokenVerifiesTrue() throws Exception {
        TokenPair pair = tokenService.issue(new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN")));
        stubSessionJson(readSid(pair.accessToken()));

        assertThat(tokenService.verifyAccessToken(pair.accessToken())).isTrue();
    }

    @Test
    @DisplayName("篡改签名令牌返回 false：布尔语义不抛异常不区分原因")
    void tamperedTokenVerifiesFalse() {
        TokenPair pair = tokenService.issue(new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN")));
        // 篡改签名段末尾两个字符（仍为合法 base64url 字符，触发签名比对失败而非格式错误）
        String tampered = pair.accessToken().substring(0, pair.accessToken().length() - 2) + "xx";

        assertThat(tokenService.verifyAccessToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("过期令牌返回 false（exp 已过与会话存在与否无关）")
    void expiredTokenVerifiesFalse() {
        TokenClaims expiredClaims = new TokenClaims(
                "123", "456", null, "expired-sid", "access", NOW.minusSeconds(1).toEpochMilli());

        assertThat(tokenService.verifyAccessToken(craftTokenWithTestHmac(expiredClaims)))
                .isFalse();
    }

    @Test
    @DisplayName("typ 不符返回 false：refresh 令牌不得当 access 令牌通过帧级鉴权（防跨类型复用）")
    void refreshTokenTypedAsAccessVerifiesFalse() throws Exception {
        TokenPair pair = tokenService.issue(new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN")));
        stubSessionJson(readSid(pair.refreshToken()));

        assertThat(tokenService.verifyAccessToken(pair.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("会话已删返回 false：登出后原 access 令牌即行失效（删除即全端失效）")
    void evictedSessionVerifiesFalse() throws Exception {
        TokenPair pair = tokenService.issue(new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN")));
        String sid = readSid(pair.accessToken());
        stubSessionJson(sid);
        // 真实登出链路删会话键（logout 内部走完整校验链后 evict）
        tokenService.logout(pair.accessToken());
        // delete 为 mock 不真实删键：显式模拟键消失后的会话读取结果
        when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid)).thenReturn(null);

        assertThat(tokenService.verifyAccessToken(pair.accessToken())).isFalse();
    }

    @Test
    @DisplayName("null 与空白令牌返回 false：握手头缺失边界不抛异常（契约防御面）")
    void nullOrBlankTokenVerifiesFalse() {
        for (String raw : new String[] {null, "", "   "}) {
            assertThat(tokenService.verifyAccessToken(raw))
                    .as("空白/缺失令牌 [%s] 应为 false", raw)
                    .isFalse();
        }
    }

    /** 桩会话 JSON：指定 sid 的会话键返回有效会话值（校验链会话存在性环节的 Redis 承载） */
    private void stubSessionJson(String sid) throws Exception {
        lenient()
                .when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid))
                .thenReturn(new ObjectMapper()
                        .writeValueAsString(new SessionData(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"))));
    }

    /** 读取令牌 claims 内的 sid（会话桩定位用） */
    private String readSid(String rawToken) throws Exception {
        byte[] payload = Base64.getUrlDecoder().decode(rawToken.split("\\.", -1)[0]);
        return new ObjectMapper()
                .readTree(payload)
                .path(SecurityConstants.CLAIM_SID)
                .asText();
    }

    /** 测试侧独立 HMAC 签名助手：按线格式自行拼装令牌，构造实现内不便直接产出的过期场景 */
    private String craftTokenWithTestHmac(TokenClaims claims) {
        try {
            byte[] payload = new ObjectMapper().writeValueAsBytes(claims);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(mac.doFinal(payload));
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("测试令牌拼装失败", e);
        }
    }
}

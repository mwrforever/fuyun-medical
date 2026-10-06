package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.api.TokenPrincipal;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.record.RefreshedAccess;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * HMAC 令牌签发/校验与 Redis 会话行为测试（D-2 方案核心路径，BRIEF-PR3-01 §1.1/§1.2 校验链全覆盖）。
 *
 * <p>覆盖：签发→校验往返、篡改签名拒绝、过期拒绝（SYS-1004）、typ 不符拒绝、会话被删拒绝（SYS-1003）、
 * 校验成功滑动续期、登出删除会话、线格式字段冻结；PR-4F 追加按角色清理会话（evictSessionsByRoles：
 * SCAN 游标遍历 + 角色交集删键 + 脏值跳过，W-96③）。Redis 以 Mockito 桩承载（真实 Redis 交互由 B3.3
 * 端到端 IT 把关）。
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    /** 测试资产假密钥（≥32 字符，仅具单测意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_SECRET = "unit-test-only-hmac-secret-0123456789abcdef";

    /** 固定当前时刻：令牌 exp 断言的确定性基准 */
    private static final Instant NOW = Instant.parse("2026-09-09T08:00:00Z");

    /** access TTL 测试值：与 properties 保持一致 */
    private static final Duration ACCESS_TTL = Duration.ofHours(2);

    /** refresh TTL 测试值 */
    private static final Duration REFRESH_TTL = Duration.ofHours(24);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @Captor
    private ArgumentCaptor<String> sessionKeyCaptor;

    @Captor
    private ArgumentCaptor<String> sessionJsonCaptor;

    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        // lenient 桩：evict/过期/格式校验路径不触达 valueOps，宽松化避免 UnnecessaryStubbing 误报
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        SecurityProperties properties = new SecurityProperties(TEST_SECRET, ACCESS_TTL, REFRESH_TTL);
        tokenService =
                new TokenServiceImpl(properties, redisTemplate, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("签发返回可用令牌对：access/refresh 两段式、claims 短键正确、会话以 access TTL 落 Redis")
    void issueProducesVerifiableTokenPairAndStoresSession() throws Exception {
        // permissions 携非空清单入会话：断言 record↔JSON 字段同名映射透传天然成立（PR-4D）
        SessionUser user =
                new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of("MENU /dashboard"));

        TokenPair pair = tokenService.issue(user);

        // 令牌对两段式格式（Base64Url payload + Base64Url HMAC，非 JWT 三段结构）
        assertThat(pair.accessToken()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(pair.refreshToken()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

        // access claims：uid 十进制字符串 / eid 员工 / oid 空账号可为 null / typ=access / exp=签发时刻+TTL
        Map<String, Object> accessClaims = readClaimsJson(pair.accessToken());
        assertThat(accessClaims.keySet())
                .containsExactlyInAnyOrder(
                        SecurityConstants.CLAIM_UID,
                        SecurityConstants.CLAIM_EID,
                        SecurityConstants.CLAIM_OID,
                        SecurityConstants.CLAIM_SID,
                        SecurityConstants.CLAIM_TYP,
                        SecurityConstants.CLAIM_EXP);
        assertThat(accessClaims.get(SecurityConstants.CLAIM_UID)).isEqualTo("123");
        assertThat(accessClaims.get(SecurityConstants.CLAIM_EID)).isEqualTo("456");
        assertThat(accessClaims.get(SecurityConstants.CLAIM_OID)).isNull();
        assertThat(accessClaims.get(SecurityConstants.CLAIM_TYP)).isEqualTo(SecurityConstants.TOKEN_TYPE_ACCESS);
        assertThat(((Number) accessClaims.get(SecurityConstants.CLAIM_EXP)).longValue())
                .isEqualTo(NOW.plus(ACCESS_TTL).toEpochMilli());

        // refresh claims：同 sid、typ=refresh、exp 取 refresh TTL
        Map<String, Object> refreshClaims = readClaimsJson(pair.refreshToken());
        assertThat(refreshClaims.get(SecurityConstants.CLAIM_SID))
                .isEqualTo(accessClaims.get(SecurityConstants.CLAIM_SID));
        assertThat(refreshClaims.get(SecurityConstants.CLAIM_TYP)).isEqualTo(SecurityConstants.TOKEN_TYPE_REFRESH);
        assertThat(((Number) refreshClaims.get(SecurityConstants.CLAIM_EXP)).longValue())
                .isEqualTo(NOW.plus(REFRESH_TTL).toEpochMilli());

        // 会话先行落 Redis：键冒号分层，TTL=access TTL（会话键必有 TTL，禁无过期键红线）
        verify(valueOps).set(sessionKeyCaptor.capture(), sessionJsonCaptor.capture(), eq(ACCESS_TTL));
        assertThat(sessionKeyCaptor.getValue())
                .isEqualTo(SecurityConstants.SESSION_KEY_PREFIX + accessClaims.get(SecurityConstants.CLAIM_SID));
        // 会话值 = JSON 序列化的 SessionData（角色摘要存会话不进令牌体）
        SessionData stored = new ObjectMapper().readValue(sessionJsonCaptor.getValue(), SessionData.class);
        assertThat(stored.userId()).isEqualTo(123L);
        assertThat(stored.loginName()).isEqualTo("admin");
        assertThat(stored.displayName()).isEqualTo("系统管理员");
        assertThat(stored.employeeId()).isEqualTo(456L);
        assertThat(stored.orgId()).isNull();
        assertThat(stored.roles()).containsExactly("ADMIN");
        assertThat(stored.permissions()).containsExactly("MENU /dashboard");
    }

    @Test
    @DisplayName("签发→校验往返：会话 JSON 还原 SessionData，且校验成功后滑动续期为 access TTL")
    void verifyRoundTripsSessionDataAndRenewsTtl() throws Exception {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        String sid = (String) readClaimsJson(pair.accessToken()).get(SecurityConstants.CLAIM_SID);
        String key = SecurityConstants.SESSION_KEY_PREFIX + sid;
        String sessionJson = new ObjectMapper()
                .writeValueAsString(
                        new SessionData(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of()));
        when(valueOps.get(key)).thenReturn(sessionJson);

        SessionData session = tokenService.verify(pair.accessToken(), SecurityConstants.TOKEN_TYPE_ACCESS);

        assertThat(session.userId()).isEqualTo(123L);
        assertThat(session.loginName()).isEqualTo("admin");
        assertThat(session.roles()).containsExactly("ADMIN");
        // 滑动续期：每次校验成功 expire 重置为 access TTL（廉价写，不做阈值判断）
        verify(redisTemplate).expire(key, ACCESS_TTL);
    }

    @Test
    @DisplayName("短期单 access 签发（大屏匿名订阅令牌）：exp=自定义 TTL、会话同 TTL 落 Redis，verifyAccessToken 全链通过")
    void issueAccessProducesShortLivedTokenPassingWsVerifyChain() throws Exception {
        SessionUser screen = new SessionUser(0L, "bigscreen", "候诊大屏", null, null, List.of(), null, List.of());
        Duration shortTtl = Duration.ofMinutes(5);

        String token = tokenService.issueAccess(screen, shortTtl);

        // 单 access 两段式线格式（与登录令牌同构，无独立令牌形态——WS 鉴权协议零改动前提）
        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        Map<String, Object> claims = readClaimsJson(token);
        assertThat(claims.get(SecurityConstants.CLAIM_TYP)).isEqualTo(SecurityConstants.TOKEN_TYPE_ACCESS);
        assertThat(((Number) claims.get(SecurityConstants.CLAIM_EXP)).longValue())
                .isEqualTo(NOW.plus(shortTtl).toEpochMilli());
        // 会话以同值 TTL 落 Redis：短期凭证的会话驻留不长于令牌本体（会话键必有 TTL 红线）
        verify(valueOps).set(sessionKeyCaptor.capture(), sessionJsonCaptor.capture(), eq(shortTtl));
        // WS CONNECT 帧鉴权入口（verifyAccessToken）对短期令牌全链通过：会话存在 + 签名 + exp + typ
        when(valueOps.get(sessionKeyCaptor.getValue())).thenReturn(sessionJsonCaptor.getValue());
        assertThat(tokenService.verifyAccessToken(token)).isTrue();
    }

    @Test
    @DisplayName("主体校验：携 wardId 哨兵令牌经会话承载返回主体三元组（W-39 通道锚点）")
    void verifyAccessPrincipalReturnsWardBoundTripleForSentinelToken() {
        // 哨兵会话携病区编码（病区屏专用）：wardId 经会话承载而非令牌体（线格式冻结，GC1）
        SessionUser screen = new SessionUser(0L, "bigscreen", "候诊大屏", null, null, List.of(), "1001", List.of());
        Duration shortTtl = Duration.ofMinutes(5);

        String token = tokenService.issueAccess(screen, shortTtl);

        // 会话回读桩：以签发时实际落库的键与会话 JSON 喂给校验链（writeSession→verify 全链真实往返）
        verify(valueOps).set(sessionKeyCaptor.capture(), sessionJsonCaptor.capture(), eq(shortTtl));
        when(valueOps.get(sessionKeyCaptor.getValue())).thenReturn(sessionJsonCaptor.getValue());

        TokenPrincipal principal = tokenService.verifyAccessPrincipal(token);

        // 主体三元组：哨兵 userId=0 + loginName 哨兵锚点 + wardId 透传（REST 限行与 WS 订阅防线比对源）
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isZero();
        assertThat(principal.loginName()).isEqualTo("bigscreen");
        assertThat(principal.wardId()).isEqualTo("1001");
    }

    @Test
    @DisplayName("主体校验：坏签名/空令牌返回 null 不抛异常（防枚举口径与 verifyAccessToken 一致）")
    void verifyAccessPrincipalReturnsNullForTamperedOrBlankToken() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        // 签名篡改样本（尾端仍为合法 base64url 字符，触发签名比对失败而非格式错误）
        String tampered = pair.accessToken().substring(0, pair.accessToken().length() - 2) + "xx";

        assertThat(tokenService.verifyAccessPrincipal(tampered)).isNull();
        // 空值守卫分支：null/空白一律 null 不抛异常（WS 帧头缺失等场景）
        assertThat(tokenService.verifyAccessPrincipal(null)).isNull();
        assertThat(tokenService.verifyAccessPrincipal("   ")).isNull();
    }

    @Test
    @DisplayName("主体校验：登录令牌 wardId 为 null（登录态会话不携病区，哨兵签发面专属）")
    void verifyAccessPrincipalYieldsNullWardForLoginToken() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);

        // 会话回读桩：以签发时实际落库的键与会话 JSON 喂给校验链
        verify(valueOps).set(sessionKeyCaptor.capture(), sessionJsonCaptor.capture(), eq(ACCESS_TTL));
        when(valueOps.get(sessionKeyCaptor.getValue())).thenReturn(sessionJsonCaptor.getValue());

        TokenPrincipal principal = tokenService.verifyAccessPrincipal(pair.accessToken());

        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo(123L);
        assertThat(principal.loginName()).isEqualTo("admin");
        assertThat(principal.wardId()).isNull();
    }

    @Test
    @DisplayName("篡改签名拒绝：重算 HMAC 常量时间比较失败即 SYS-1003/401")
    void tamperedSignatureIsRejected() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        // 篡改签名段末尾两个字符（仍为合法 base64url 字符，触发重算比对失败而非格式错误）
        String tampered = pair.accessToken().substring(0, pair.accessToken().length() - 2) + "xx";

        assertThatThrownBy(() -> tokenService.verify(tampered, SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("篡改载荷拒绝：改动 payload 一字节后签名比对失败")
    void tamperedPayloadIsRejected() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        // 交换 payload 段首两个字符：payload 仍可解码但与签名不再匹配（防"改 uid 越权"攻击面）
        String[] parts = pair.accessToken().split("\\.", -1);
        String tamperedPayload = "xx" + parts[0].substring(2);

        assertThatThrownBy(() ->
                        tokenService.verify(tamperedPayload + "." + parts[1], SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID));
    }

    @Test
    @DisplayName("过期令牌拒绝：exp 已过返回 SYS-1004/401（与会话存在与否无关）")
    void expiredTokenIsRejectedWithExpiredCode() {
        long expiredAt = NOW.minusSeconds(1).toEpochMilli();
        TokenClaims expiredClaims =
                new TokenClaims("123", "456", null, "expired-sid", SecurityConstants.TOKEN_TYPE_ACCESS, expiredAt);
        String rawToken = craftTokenWithTestHmac(expiredClaims);

        assertThatThrownBy(() -> tokenService.verify(rawToken, SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.TOKEN_EXPIRED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("typ 严格匹配：access 令牌按 refresh 校验（及反向）均拒绝")
    void typeMismatchIsRejected() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);

        assertThatThrownBy(() -> tokenService.verify(pair.refreshToken(), SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID));
        assertThatThrownBy(() -> tokenService.verify(pair.accessToken(), SecurityConstants.TOKEN_TYPE_REFRESH))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID));
    }

    @Test
    @DisplayName("会话被删拒绝：登出/踢出后 Redis 键不存在即 SYS-1003（删除即全端失效）")
    void missingSessionIsRejected() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        when(valueOps.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        assertThatThrownBy(() -> tokenService.verify(pair.accessToken(), SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    @DisplayName("refresh 令牌按 refresh 类型校验成功（刷新换发的令牌校验侧前置能力）")
    void refreshTokenVerifiesAgainstRefreshType() throws Exception {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        String sid = (String) readClaimsJson(pair.refreshToken()).get(SecurityConstants.CLAIM_SID);
        when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid))
                .thenReturn(new ObjectMapper()
                        .writeValueAsString(new SessionData(
                                123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of())));

        SessionData session = tokenService.verify(pair.refreshToken(), SecurityConstants.TOKEN_TYPE_REFRESH);

        assertThat(session.userId()).isEqualTo(123L);
        // refresh 校验成功同样续满（会话 TTL 重置为 access TTL）
        verify(redisTemplate).expire(SecurityConstants.SESSION_KEY_PREFIX + sid, ACCESS_TTL);
    }

    @Test
    @DisplayName("格式非法拒绝：无点分段/多段/非 base64url 均 SYS-1003，不触达签名与会话")
    void malformedTokensAreRejected() {
        for (String raw : new String[] {"", "no-dot-token", "a.b.c", "###.###", ".sig-only"}) {
            assertThatThrownBy(() -> tokenService.verify(raw, SecurityConstants.TOKEN_TYPE_ACCESS))
                    .as("格式非法令牌 [%s] 应被拒绝", raw)
                    .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                            .isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID));
        }
    }

    @Test
    @DisplayName("签名有效但载荷非 JSON 拒绝：伪造合法签名+畸形载荷不得绕过校验（SYS-1003）")
    void garbagePayloadWithValidSignatureIsRejected() {
        // 测试侧 HMAC 签名"非 JSON 载荷"：签名比对可通过，载荷解析必须失败拒绝（防探测面收敛）
        String crafted = craftTokenWithTestHmac("not-a-json-payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tokenService.verify(crafted, SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        // 载荷解析失败发生在会话读取之前：Redis 不得被触达
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("会话 JSON 损坏拒绝：合法令牌+损坏会话值按 SYS-1003 处置且失败路径不续期")
    void corruptedSessionValueIsRejected() throws Exception {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        String sid = (String) readClaimsJson(pair.accessToken()).get(SecurityConstants.CLAIM_SID);
        // 真实令牌 + Redis 会话值损坏（存储层脏数据/被外力篡改的兜底防线）
        when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid)).thenReturn("{\"corrupted\":");

        assertThatThrownBy(() -> tokenService.verify(pair.accessToken(), SecurityConstants.TOKEN_TYPE_ACCESS))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        // 校验失败即拒绝：滑动续期不得执行（只有校验全通过才续满）
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("登出删除会话键：access 与 refresh 同 sid 同时失效的删除入口")
    void evictDeletesSessionKey() {
        tokenService.evict("some-session-id");

        verify(redisTemplate).delete(SecurityConstants.SESSION_KEY_PREFIX + "some-session-id");
    }

    @Test
    @DisplayName("刷新换发成功：refresh 令牌换发同 sid 新 access，可按 access 类型校验且会话续期")
    void refreshAccessTokenMintsSameSidAccessToken() throws Exception {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        String sid = (String) readClaimsJson(pair.refreshToken()).get(SecurityConstants.CLAIM_SID);
        when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid))
                .thenReturn(new ObjectMapper()
                        .writeValueAsString(new SessionData(
                                123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of())));

        RefreshedAccess refreshed = tokenService.refreshAccessToken(pair.refreshToken());

        // 新 access 与原 refresh 同 sid、typ=access、exp=当前时刻+access TTL
        Map<String, Object> accessClaims = readClaimsJson(refreshed.accessToken());
        assertThat(accessClaims.get(SecurityConstants.CLAIM_SID)).isEqualTo(sid);
        assertThat(accessClaims.get(SecurityConstants.CLAIM_TYP)).isEqualTo(SecurityConstants.TOKEN_TYPE_ACCESS);
        assertThat(((Number) accessClaims.get(SecurityConstants.CLAIM_EXP)).longValue())
                .isEqualTo(NOW.plus(ACCESS_TTL).toEpochMilli());
        // 会话状态还原（角色摘要不进令牌体，由会话承载）
        assertThat(refreshed.session().userId()).isEqualTo(123L);
        assertThat(refreshed.session().roles()).containsExactly("ADMIN");
        // 新 access 可按 access 类型通过完整校验链（含会话存在性）
        assertThat(tokenService
                        .verify(refreshed.accessToken(), SecurityConstants.TOKEN_TYPE_ACCESS)
                        .userId())
                .isEqualTo(123L);
    }

    @Test
    @DisplayName("刷新失败统一 SYS-1005：typ 不符/签名篡改均按刷新令牌无效拒绝（防错误细分探测）")
    void refreshAccessTokenRejectsInvalidRefreshTokenWithUnifiedCode() {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        // access 令牌按 refresh 用途提交：typ 不符属刷新失败
        String wrongTypeRaw = pair.accessToken();
        // 签名篡改样本（尾端仍为合法 base64url 字符，触发签名比对失败而非格式错误）
        String tampered = pair.refreshToken().substring(0, pair.refreshToken().length() - 2) + "xx";

        for (String raw : new String[] {wrongTypeRaw, tampered}) {
            assertThatThrownBy(() -> tokenService.refreshAccessToken(raw))
                    .as("非法刷新令牌 [%s...] 应统一按 SYS-1005 拒绝", raw.substring(0, 10))
                    .isInstanceOfSatisfying(BizException.class, ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.REFRESH_TOKEN_INVALID);
                        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    });
        }
    }

    @Test
    @DisplayName("登出校验 typ=access 后删键：合法 access 登出删除会话；refresh 令牌登出被拒绝且不删键")
    void logoutRequiresAccessTokenAndDeletesSession() throws Exception {
        SessionUser user = new SessionUser(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of());
        TokenPair pair = tokenService.issue(user);
        String sid = (String) readClaimsJson(pair.accessToken()).get(SecurityConstants.CLAIM_SID);
        when(valueOps.get(SecurityConstants.SESSION_KEY_PREFIX + sid))
                .thenReturn(new ObjectMapper()
                        .writeValueAsString(new SessionData(
                                123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of())));

        // 合法 access 登出：删除同 sid 会话键（refresh 同失效）
        tokenService.logout(pair.accessToken());
        verify(redisTemplate).delete(SecurityConstants.SESSION_KEY_PREFIX + sid);

        // refresh 令牌登出：typ 强校验拒绝（§8-11 刷新/登出须校验 typ），不得触发删除
        reset(redisTemplate);
        assertThatThrownBy(() -> tokenService.logout(pair.refreshToken()))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.TOKEN_MISSING_OR_INVALID));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("按角色清理会话：删匹配键留他者（NURSE×2 清 2，DOCTOR 会话保留）")
    void evictSessionsByRolesDeletesMatchingSessionsAndKeepsOthers() {
        // 三会话：NURSE×2 + DOCTOR×1 → 清 NURSE 计 2，DOCTOR 保留
        String nurseJson =
                "{\"userId\":11,\"loginName\":\"nursedemo\",\"displayName\":\"护\",\"employeeId\":11,\"orgId\":1,\"roles\":[\"NURSE\"],\"wardId\":null,\"permissions\":[]}";
        String doctorJson =
                "{\"userId\":3,\"loginName\":\"doctordemo\",\"displayName\":\"医\",\"employeeId\":3,\"orgId\":1,\"roles\":[\"ADMIN\",\"DOCTOR\"],\"wardId\":null,\"permissions\":[]}";
        stubScanExecute();
        Cursor<byte[]> cursor = cursorOf("fy:system:session:s1", "fy:system:session:s2", "fy:system:session:s3");
        when(redisConnection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.get("fy:system:session:s1")).thenReturn(nurseJson);
        when(valueOps.get("fy:system:session:s2")).thenReturn(nurseJson);
        when(valueOps.get("fy:system:session:s3")).thenReturn(doctorJson);

        int evicted = tokenService.evictSessionsByRoles(Set.of("NURSE"));

        assertThat(evicted).isEqualTo(2);
        verify(redisTemplate).delete("fy:system:session:s1");
        verify(redisTemplate).delete("fy:system:session:s2");
        verify(redisTemplate, never()).delete("fy:system:session:s3"); // DOCTOR 会话保留
    }

    @Test
    @DisplayName("空交集零删除：全部会话角色与目标集无交集时返回 0，会话键全保留")
    void evictSessionsByRolesReturnsZeroWhenNoSessionRoleMatches() {
        // DOCTOR 会话对 PHARMACIST 目标集：交集为空，不触发任何删除
        String doctorJson =
                "{\"userId\":3,\"loginName\":\"doctordemo\",\"displayName\":\"医\",\"employeeId\":3,\"orgId\":1,\"roles\":[\"ADMIN\",\"DOCTOR\"],\"wardId\":null,\"permissions\":[]}";
        stubScanExecute();
        Cursor<byte[]> cursor = cursorOf("fy:system:session:s3");
        when(redisConnection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.get("fy:system:session:s3")).thenReturn(doctorJson);

        int evicted = tokenService.evictSessionsByRoles(Set.of("PHARMACIST"));

        assertThat(evicted).isZero();
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("非法 JSON 跳过续处理：脏会话键 warn 留痕不抛，后续键继续清理（单键脏数据不阻断整批）")
    void evictSessionsByRolesSkipsCorruptedSessionValueAndContinues() {
        // 首键存储层脏数据（非 JSON），次键正常命中目标角色：证明脏键跳过后处理链未中断
        String nurseJson =
                "{\"userId\":11,\"loginName\":\"nursedemo\",\"displayName\":\"护\",\"employeeId\":11,\"orgId\":1,\"roles\":[\"NURSE\"],\"wardId\":null,\"permissions\":[]}";
        stubScanExecute();
        Cursor<byte[]> cursor = cursorOf("fy:system:session:bad", "fy:system:session:s2");
        when(redisConnection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.get("fy:system:session:bad")).thenReturn("{\"corrupted");
        when(valueOps.get("fy:system:session:s2")).thenReturn(nurseJson);

        int evicted = tokenService.evictSessionsByRoles(Set.of("NURSE"));

        assertThat(evicted).isEqualTo(1);
        verify(redisTemplate).delete("fy:system:session:s2");
        verify(redisTemplate, never()).delete("fy:system:session:bad");
    }

    @Test
    @DisplayName("SCAN 游标而非 KEYS 全量：match 模式收敛会话键前缀（Redis 命令红线，批大小细节作评审锚）")
    void evictSessionsByRolesScansWithSessionKeyPatternInsteadOfKeys() {
        String nurseJson =
                "{\"userId\":11,\"loginName\":\"nursedemo\",\"displayName\":\"护\",\"employeeId\":11,\"orgId\":1,\"roles\":[\"NURSE\"],\"wardId\":null,\"permissions\":[]}";
        stubScanExecute();
        Cursor<byte[]> cursor = cursorOf("fy:system:session:s1");
        when(redisConnection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.get("fy:system:session:s1")).thenReturn(nurseJson);

        int evicted = tokenService.evictSessionsByRoles(Set.of("NURSE"));

        // 断言锚定业务结果（计数与删除键集在其余用例承载），此处锚定扫描入口合规性：
        // SCAN 携带会话键前缀 match；count 批大小等实现细节不在此锁定（代码评审锚）
        assertThat(evicted).isEqualTo(1);
        ArgumentCaptor<ScanOptions> optionsCaptor = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redisConnection).scan(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getPattern()).isEqualTo(SecurityConstants.SESSION_KEY_PREFIX + "*");
        // 禁 KEYS 红线：连接层不得出现 keys 全量调用
        verify(redisConnection, never()).keys(any());
    }

    /** 桩化 SCAN 执行链：execute 回调直连 mock 连接（回调真执行非直返），SCAN 语义由连接 stub 承载 */
    @SuppressWarnings("unchecked")
    private void stubScanExecute() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> callback = inv.getArgument(0);
            return callback.doInRedis(redisConnection); // 回调直连 mock 连接，SCAN 语义由 stub 承载
        });
    }

    /** 构造按序产出指定键的 SCAN 游标 stub（hasNext/next 闭合，close 走 mock 默认 no-op） */
    @SuppressWarnings("unchecked")
    private Cursor<byte[]> cursorOf(String... keys) {
        Cursor<byte[]> cursor = mock(Cursor.class);
        Iterator<String> remaining = List.of(keys).iterator();
        when(cursor.hasNext()).thenAnswer(inv -> remaining.hasNext());
        when(cursor.next()).thenAnswer(inv -> remaining.next().getBytes(StandardCharsets.UTF_8));
        return cursor;
    }

    /** 读取令牌 payload 段 JSON（base64url 解码 + Map 承载，断言线格式字段全集） */
    private Map<String, Object> readClaimsJson(String rawToken) throws Exception {
        String payloadPart = rawToken.split("\\.", -1)[0];
        byte[] payload = Base64.getUrlDecoder().decode(payloadPart);
        return new ObjectMapper().readValue(payload, Map.class);
    }

    /** 测试侧独立 HMAC 签名助手（字节层核心）：按简报线格式自行拼装令牌，构造实现内不便直接产出的场景 */
    private String craftTokenWithTestHmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("测试令牌拼装失败", e);
        }
    }

    /** claims 载荷便捷重载：序列化后经字节层助手签名（与实现线格式同构，独立实现可交叉验证） */
    private String craftTokenWithTestHmac(TokenClaims claims) {
        try {
            return craftTokenWithTestHmac(new ObjectMapper().writeValueAsBytes(claims));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试令牌拼装失败", e);
        }
    }
}

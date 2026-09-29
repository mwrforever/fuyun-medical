package com.fuyun.outpatient.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * portal 证件号频控守卫单测（EX-29 临时缓解②，BE-A3-02 裁决③）：计数与冷却的 Redis 键契约
 * （前缀/摘要成分——证件号明文禁入键）、阈值判定（连续失败 5 次置冷却）、成功清零（连续语义
 * 打断面）、冷却期内 429 OP-1023 拒绝、窗口过期恢复与 Redis 异常降级放行（StormGuard 同款
 * 辅助语义口径）。真栈 TTL 语义由部署环境验证，本单测锚定判定面契约。
 */
@ExtendWith(MockitoExtension.class)
class PortalCredentialRateGuardTest {

    /** 测试用证件号（虚构样例，仅承载键成分断言） */
    private static final String CREDENTIAL_TYPE = "ID_CARD";

    private static final String CREDENTIAL_NO = "110101199001011234";

    /** 失败计数键（与主类同源拼装规则：前缀 + SHA-256(类型:介质号)） */
    private static final String FAIL_KEY =
            "fy:outpatient:portal-cred-fail:" + sha256(CREDENTIAL_TYPE + ":" + CREDENTIAL_NO);

    /** 冷却标记键（与主类同源拼装规则） */
    private static final String COOL_KEY =
            "fy:outpatient:portal-cred-cool:" + sha256(CREDENTIAL_TYPE + ":" + CREDENTIAL_NO);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PortalCredentialRateGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PortalCredentialRateGuard(redisTemplate);
    }

    @Test
    @DisplayName("无冷却标记放行：hasKey=false 不抛，键成分为摘要（不含证件号明文）")
    void passesWhenNoCooldownMarker() {
        when(redisTemplate.hasKey(COOL_KEY)).thenReturn(false);

        assertThatCode(() -> guard.checkNotCoolingDown(CREDENTIAL_TYPE, CREDENTIAL_NO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("键成分脱敏断言：冷却/计数键均为 SHA-256 摘要成分，证件号明文禁入 Redis 键")
    void redisKeysNeverContainPlaintextCredential() {
        assertThat(FAIL_KEY)
                .startsWith("fy:outpatient:portal-cred-fail:")
                .doesNotContain(CREDENTIAL_NO)
                .hasSize("fy:outpatient:portal-cred-fail:".length() + 64);
        assertThat(COOL_KEY)
                .startsWith("fy:outpatient:portal-cred-cool:")
                .doesNotContain(CREDENTIAL_NO)
                .hasSize("fy:outpatient:portal-cred-cool:".length() + 64);
    }

    @Test
    @DisplayName("冷却中拒绝：冷却标记在挂即抛 OP-1023 429（匿名枚举试错被前置阻断）")
    void rejectsAs429WhenCoolingDown() {
        when(redisTemplate.hasKey(COOL_KEY)).thenReturn(true);

        assertThatThrownBy(() -> guard.checkNotCoolingDown(CREDENTIAL_TYPE, CREDENTIAL_NO))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PORTAL_CREDENTIAL_RATE_LIMITED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    @DisplayName("连续失败达阈值（第 5 次）置冷却标记：INCR+每次续期窗口 TTL+SET 冷却键 TTL=30m")
    void setsCooldownMarkerWhenConsecutiveFailuresReachThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(FAIL_KEY)).thenReturn(5L);

        guard.recordResolutionFailure(CREDENTIAL_TYPE, CREDENTIAL_NO);

        // 计数面断言：INCR 计数键 + 每次失败续期 10 分钟窗口（禁无过期键）
        verify(valueOperations).increment(FAIL_KEY);
        verify(redisTemplate).expire(FAIL_KEY, Duration.ofMinutes(10));
        // 冷却面断言：达阈值置冷却标记（TTL=30m，与登录锁定时长同锚）
        verify(valueOperations).set(COOL_KEY, "1", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("未达阈值（第 4 次）不置冷却标记：计数续期但冷却键零写")
    void doesNotSetCooldownBelowThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(FAIL_KEY)).thenReturn(4L);

        guard.recordResolutionFailure(CREDENTIAL_TYPE, CREDENTIAL_NO);

        verify(redisTemplate).expire(FAIL_KEY, Duration.ofMinutes(10));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("窗口过期恢复：计数键 TTL 到期后重新计数（新窗口首败=1），不再处于冷却即放行且不重置冷却")
    void freshWindowRestartsCountAfterExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(FAIL_KEY)).thenReturn(1L);
        when(redisTemplate.hasKey(COOL_KEY)).thenReturn(false);

        // 冷却标记已到期（30m 冷却 > 10m 窗口，冷却结束时计数键必然已先过期）→ 新窗口首败计数 1
        assertThatCode(() -> guard.checkNotCoolingDown(CREDENTIAL_TYPE, CREDENTIAL_NO))
                .doesNotThrowAnyException();
        guard.recordResolutionFailure(CREDENTIAL_TYPE, CREDENTIAL_NO);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("解析成功清零计数：删除失败计数键（连续语义的成功打断面），冷却标记不动")
    void clearsFailureCountOnSuccess() {
        guard.clearFailureCount(CREDENTIAL_TYPE, CREDENTIAL_NO);

        verify(redisTemplate).delete(FAIL_KEY);
        verify(redisTemplate, never()).delete(COOL_KEY);
    }

    @Test
    @DisplayName("Redis 异常降级放行：hasKey 抛异常不阻断受理（频控为辅助语义，StormGuard 同款）")
    void degradesToPassThroughWhenRedisFails() {
        when(redisTemplate.hasKey(COOL_KEY)).thenThrow(new RedisConnectionFailureException("连接失败"));

        assertThatCode(() -> guard.checkNotCoolingDown(CREDENTIAL_TYPE, CREDENTIAL_NO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 计数异常降级跳过：increment 抛异常不外抛（本窗口少计一次，下次失败自愈续期）")
    void skipsCountingWhenRedisIncrementFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(FAIL_KEY)).thenThrow(new RedisConnectionFailureException("连接失败"));

        assertThatCode(() -> guard.recordResolutionFailure(CREDENTIAL_TYPE, CREDENTIAL_NO))
                .doesNotThrowAnyException();
        // 异常即中断：续期与冷却置位零触达（降级不产生半套计数状态）
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /**
     * 计算文本的 SHA-256 小写 hex 摘要（与主类键成分同源算法，键断言的期望值来源）。
     *
     * @param text 原文（介质类型:介质号），非空
     * @return 64 位小写十六进制摘要，非空
     */
    private static String sha256(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("测试摘要算法不可用（JDK 环境异常）", e);
        }
    }
}

package com.fuyun.nursing.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
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
 * 护理频控守卫单测（PR-4E Task 6，A-6 上报限频 + A-8 PDA 枚举冷却）：双方法族 Redis 键契约
 * （fy:nursing: 前缀 + SHA-256 摘要成分——operatorId/identifier 明文禁入键）、窗口计数判定
 * （每次 EXPIRE 续期滑窗自愈——评审 C-F2/A-2 修复后契约、恰上限放行/超阈拒绝）、连续失败
 * 阈值置冷却（第 5 次置 30m 冷却标记）、
 * 成功清零（连续语义打断面）、冷却中 429 NS-1030 拒绝与 Redis 异常降级放行（效率层防线不阻断
 * 医护主链路，StormGuard 同款口径）。真栈 TTL 语义由部署环境验证，本单测锚定判定面契约。
 */
@ExtendWith(MockitoExtension.class)
class NursingRateGuardTest {

    /** A-6 上报频控业务段（checkWithinWindow 窗口计数族） */
    private static final String REPORT_SPACE = "report-freq";

    /** A-8 PDA 枚举探测业务段（checkNotCooling/recordProbeFailure 冷却族） */
    private static final String PDA_SPACE = "pda-probe";

    /** 上报操作者（operatorId 键成分——非敏感也统一摘要，简单一致） */
    private static final String OPERATOR = "1001";

    /** PDA 扫码标识（证件号样例——敏感字段明文禁入 Redis 键与日志） */
    private static final String IDENTIFIER = "110101199001011234";

    /** A-6 窗口计数键（与主类同源拼装规则：前缀 + SHA-256(业务段:键成分)） */
    private static final String REPORT_COUNT_KEY = "fy:nursing:report-freq:" + sha256(REPORT_SPACE + ":" + OPERATOR);

    /** A-8 失败计数键（与主类同源拼装规则） */
    private static final String PDA_FAIL_KEY = "fy:nursing:pda-probe:" + sha256(PDA_SPACE + ":" + IDENTIFIER);

    /** A-8 冷却标记键（与主类同源拼装规则：业务段后拼 -cool，摘要成分与计数键同源） */
    private static final String PDA_COOL_KEY = "fy:nursing:pda-probe-cool:" + sha256(PDA_SPACE + ":" + IDENTIFIER);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private NursingRateGuard guard;

    @BeforeEach
    void setUp() {
        guard = new NursingRateGuard(redisTemplate);
    }

    // ===================== 窗口计数族（A-6 上报限频） =====================

    @Test
    @DisplayName("窗口内放行：首次计数 INCR=1 且 ≤ 上限返回 true，计数即续期 60s 窗口 TTL（禁无过期键）")
    void windowCountPassesWithinLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(REPORT_COUNT_KEY)).thenReturn(1L);

        assertThat(guard.checkWithinWindow(
                        REPORT_SPACE, OPERATOR, NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS))
                .isTrue();

        // 计数面断言：INCR 计数键 + 计数即续期 60 秒窗口（滑窗自愈——每次调用都 expire）
        verify(valueOperations).increment(REPORT_COUNT_KEY);
        verify(redisTemplate).expire(REPORT_COUNT_KEY, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("恰阈值边界：第 10 次计数=上限仍放行 true（count<limit 误改即第 10 次误拒不红）")
    void windowCountPassesAtExactLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(REPORT_COUNT_KEY)).thenReturn(10L);

        assertThat(guard.checkWithinWindow(
                        REPORT_SPACE, OPERATOR, NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS))
                .isTrue();

        // ≤ 语义边界锚：上限 10 次本身合法（第 11 次才拒），且恰阈值计数同样续期窗口
        verify(redisTemplate).expire(REPORT_COUNT_KEY, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("超阈拒绝：窗口内第 11 次计数返回 false（A-6 每操作者 10 次/分钟上限），计数仍续期窗口（滑窗自愈）")
    void windowCountRejectsOverLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(REPORT_COUNT_KEY)).thenReturn(11L);

        assertThat(guard.checkWithinWindow(
                        REPORT_SPACE, OPERATOR, NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS))
                .isFalse();

        // 滑窗自愈锚（评审 C-F2/A-2 修复，D-21 断言现代化）：超阈计数也无条件续期 TTL——
        // 仅 count==1 置 TTL 时 INCR 后 EXPIRE 失败会遗留无 TTL 永久键，该操作者第 11 次
        // 起永久 429 无自愈出口；每次续期后 EXPIRE 失败遗留的键由任意后续计数自愈续期
        verify(redisTemplate).expire(REPORT_COUNT_KEY, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("窗口计数 Redis 异常降级放行：increment 抛异常返回 true（效率层防线不阻断上报主链路）")
    void windowCountDegradesToPassOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(REPORT_COUNT_KEY)).thenThrow(new RedisConnectionFailureException("连接失败"));

        assertThat(guard.checkWithinWindow(
                        REPORT_SPACE, OPERATOR, NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS))
                .isTrue();
    }

    @Test
    @DisplayName("盒装 increment 判空：null 返回放行 true 且零续期（降级场景 NPE 防线）")
    void windowCountTreatsNullIncrementAsPass() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(REPORT_COUNT_KEY)).thenReturn(null);

        assertThat(guard.checkWithinWindow(
                        REPORT_SPACE, OPERATOR, NursingRateGuard.REPORT_LIMIT, NursingRateGuard.REPORT_WINDOW_MS))
                .isTrue();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    // ===================== 冷却族（A-8 PDA 枚举探测） =====================

    @Test
    @DisplayName("无冷却标记放行：hasKey=false 不抛，键成分为摘要（不含标识明文）")
    void passesWhenNoCooldownMarker() {
        when(redisTemplate.hasKey(PDA_COOL_KEY)).thenReturn(false);

        assertThatCode(() -> guard.checkNotCooling(PDA_SPACE, IDENTIFIER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("冷却中拒绝：冷却标记在挂即抛 NS-1030 429（枚举试错被前置阻断于解析之前）")
    void rejectsAs429WhenCooling() {
        when(redisTemplate.hasKey(PDA_COOL_KEY)).thenReturn(true);

        assertThatThrownBy(() -> guard.checkNotCooling(PDA_SPACE, IDENTIFIER))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(NursingErrorCode.PDA_PROBE_COOLING);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("NS-1030");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    @DisplayName("冷却查询 Redis 异常降级放行：hasKey 抛异常不阻断（频控为辅助语义，StormGuard 同款）")
    void degradesToPassThroughWhenRedisFails() {
        when(redisTemplate.hasKey(PDA_COOL_KEY)).thenThrow(new RedisConnectionFailureException("连接失败"));

        assertThatCode(() -> guard.checkNotCooling(PDA_SPACE, IDENTIFIER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("连续失败达阈值（第 5 次）置冷却标记：INCR+每次续期 10m 计数窗口+SET 冷却键 TTL=30m")
    void setsCooldownMarkerWhenConsecutiveFailuresReachThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(PDA_FAIL_KEY)).thenReturn(5L);

        guard.recordProbeFailure(PDA_SPACE, IDENTIFIER, NursingRateGuard.PDA_THRESHOLD, NursingRateGuard.PDA_COOL_MS);

        // 计数面断言：INCR 计数键 + 每次失败续期 10 分钟窗口（禁无过期键）
        verify(valueOperations).increment(PDA_FAIL_KEY);
        verify(redisTemplate).expire(PDA_FAIL_KEY, Duration.ofMinutes(10));
        // 冷却面断言：达阈值置冷却标记（TTL=30m，窗口不变式冷却 30m>计数窗口 10m）
        verify(valueOperations).set(PDA_COOL_KEY, "1", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("未达阈值（第 4 次）不置冷却标记：计数续期但冷却键零写")
    void doesNotSetCooldownBelowThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(PDA_FAIL_KEY)).thenReturn(4L);

        guard.recordProbeFailure(PDA_SPACE, IDENTIFIER, NursingRateGuard.PDA_THRESHOLD, NursingRateGuard.PDA_COOL_MS);

        verify(redisTemplate).expire(PDA_FAIL_KEY, Duration.ofMinutes(10));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("窗口过期恢复：冷却到期（30m>10m——冷却结束时计数键必然已先过期）后新窗口首败=1 不再冷却")
    void freshWindowRestartsCountAfterExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(PDA_FAIL_KEY)).thenReturn(1L);
        when(redisTemplate.hasKey(PDA_COOL_KEY)).thenReturn(false);

        // 冷却标记已到期 → 放行；新窗口首败计数 1（不置冷却）
        assertThatCode(() -> guard.checkNotCooling(PDA_SPACE, IDENTIFIER)).doesNotThrowAnyException();
        guard.recordProbeFailure(PDA_SPACE, IDENTIFIER, NursingRateGuard.PDA_THRESHOLD, NursingRateGuard.PDA_COOL_MS);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("解析成功清零计数：删除失败计数键（连续语义的成功打断面），冷却标记不动")
    void clearsFailureCountOnSuccess() {
        guard.clearFailureCount(PDA_SPACE, IDENTIFIER);

        verify(redisTemplate).delete(PDA_FAIL_KEY);
        verify(redisTemplate, never()).delete(PDA_COOL_KEY);
    }

    @Test
    @DisplayName("失败计数 Redis 异常降级跳过：increment 抛异常不外抛（本窗口少计一次，下次失败自愈续期）")
    void skipsCountingWhenRedisIncrementFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(PDA_FAIL_KEY)).thenThrow(new RedisConnectionFailureException("连接失败"));

        assertThatCode(() -> guard.recordProbeFailure(
                        PDA_SPACE, IDENTIFIER, NursingRateGuard.PDA_THRESHOLD, NursingRateGuard.PDA_COOL_MS))
                .doesNotThrowAnyException();
        // 异常即中断：续期与冷却置位零触达（降级不产生半套计数状态）
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ===================== 键摘要契约（红线锚） =====================

    @Test
    @DisplayName("键成分脱敏断言：窗口/失败计数/冷却键均为 SHA-256 摘要成分，明文键成分禁入 Redis 键")
    void redisKeysNeverContainPlaintextKey() {
        assertThat(REPORT_COUNT_KEY)
                .startsWith("fy:nursing:report-freq:")
                .doesNotContain(OPERATOR)
                .hasSize("fy:nursing:report-freq:".length() + 64);
        assertThat(PDA_FAIL_KEY)
                .startsWith("fy:nursing:pda-probe:")
                .doesNotContain(IDENTIFIER)
                .hasSize("fy:nursing:pda-probe:".length() + 64);
        assertThat(PDA_COOL_KEY)
                .startsWith("fy:nursing:pda-probe-cool:")
                .doesNotContain(IDENTIFIER)
                .hasSize("fy:nursing:pda-probe-cool:".length() + 64);
    }

    /**
     * 计算文本的 SHA-256 小写 hex 摘要（与主类键成分同源算法，键断言的期望值来源）。
     *
     * @param text 原文（业务段:键成分），非空
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

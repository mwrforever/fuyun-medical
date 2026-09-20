package com.fuyun.outpatient.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 号源池 Redis 预扣闸单测（M03 Spec 3.2 双道闸第一道，Task 4 冻结用例集）：deduct 三态返回
 * （扣减后余量 / -1 余量不足 / -2 键缺失=调用方降级信号）、release 越界封顶透传、每次执行必携
 * TTL（EXPIRE 生效前提）、prime 预热 SET total+TTL。Lua 脚本本体语义由真栈集成测试验证，
 * 本单测锚定门面契约（脚本返回值透传 + 键命名 + 参数序列化）。
 */
@ExtendWith(MockitoExtension.class)
class PoolRedisGateTest {

    /** 号源池行 id（键命名断言锚） */
    private static final long POOL_ID = 9L;

    /** 池总量（扣减谓词与回补封顶锚） */
    private static final long TOTAL = 4L;

    /** 测试 TTL 窗（与主类调用方传参同量级） */
    private static final Duration TTL = Duration.ofHours(6);

    /** 与主类键命名同源（fy:outpatient:pool:{poolId}，A.5-1 冒号分层 + A.5-17 hash tag） */
    private static final String POOL_KEY = "fy:outpatient:pool:{" + POOL_ID + "}";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PoolRedisGate gate;

    @BeforeEach
    void setUp() {
        gate = new PoolRedisGate(redisTemplate);
    }

    /** Lua 脚本执行桩：三参 varargs 形态（扣减量/总量/TTL 秒）定绑 execute(RedisScript, List, Object...) 重载 */
    private void stubScriptReturn(long returnValue) {
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(returnValue);
    }

    @Test
    @DisplayName("deduct：脚本返回 3（total=4 扣减 1 后余量）——门面透传扣减后余量")
    void deductReturnsRemainingAfterAtomicDecr() {
        stubScriptReturn(3L);

        assertThat(gate.deduct(POOL_ID, TOTAL, TTL)).isEqualTo(3);
    }

    @Test
    @DisplayName("deduct：余量 0 脚本返回 -1——透传余量不足信号（调用方判 OP-1003）")
    void deductReturnsMinusOneWhenPoolEmpty() {
        stubScriptReturn(-1L);

        assertThat(gate.deduct(POOL_ID, TOTAL, TTL)).isEqualTo(-1);
    }

    @Test
    @DisplayName("deduct：键缺失脚本返回 -2——透传降级信号（放号未预热/键已过期，调用方降级直连 DB）")
    void deductReturnsMinusTwoWhenKeyMissing() {
        stubScriptReturn(-2L);

        assertThat(gate.deduct(POOL_ID, TOTAL, TTL)).isEqualTo(-2);
    }

    @Test
    @DisplayName("release：余量 total-1 回补 2 脚本封顶返回 total——透传封顶后余量（对账防漂移）")
    void releaseCapsAtTotalQuota() {
        stubScriptReturn(TOTAL);

        assertThat(gate.release(POOL_ID, TOTAL, TTL)).isEqualTo(4);
    }

    @Test
    @DisplayName("deduct：每次执行必携 EXPIRE——键命名/hash tag、扣减量 1、池总量与 TTL 秒参数断言")
    void deductAlwaysAppliesTtl() {
        stubScriptReturn(2L);

        gate.deduct(POOL_ID, TOTAL, TTL);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> deductCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> totalCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate)
                .execute(
                        any(RedisScript.class),
                        keysCaptor.capture(),
                        deductCaptor.capture(),
                        totalCaptor.capture(),
                        ttlCaptor.capture());
        // 键命名：fy:outpatient:pool:{poolId}——{poolId} 为 Redis Cluster hash tag（A.5-17）
        assertThat(keysCaptor.getValue()).containsExactly(POOL_KEY);
        assertThat(deductCaptor.getValue()).isEqualTo("1");
        assertThat(totalCaptor.getValue()).isEqualTo("4");
        // EXPIRE 生效前提：TTL 秒参数与请求 TTL 同值且为正（禁无过期键，A.5-1）
        assertThat(Long.parseLong(ttlCaptor.getValue())).isEqualTo(TTL.toSeconds());
        assertThat(Long.parseLong(ttlCaptor.getValue())).isPositive();
    }

    @Test
    @DisplayName("prime：放号预热 SET total+TTL——键缺失态由本调用消除（deduct -2 分支的逆操作）")
    void primeSeedsRemainingWithTotalAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        gate.prime(POOL_ID, TOTAL, TTL);

        verify(valueOperations).set(POOL_KEY, "4", TTL);
    }
}

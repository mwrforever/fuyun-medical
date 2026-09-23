package com.fuyun.nursing.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 护理业务单号发号器单测：前缀/日期/五位序号格式冻结、逐次 48h TTL 续期、未知类型拒绝发号。
 *
 * <p>StringRedisTemplate 以 Mockito mock 承载（不起真实 Redis，PatientCacheServiceTest 同款）；
 * 日期断言取服务器当日 {@code LocalDate.now()} 拼期望值（跨零点窗口的抖动概率可忽略）。
 */
@ExtendWith(MockitoExtension.class)
class NursingSeqGateTest {

    /** 与实现的键命名契约冻结：fy:nursing:seq:{type}:{yyyyMMdd}（A.5-1 命名法） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("NR 类型发号：返回 前缀+当日 yyyyMMdd+五位序号（INCR=1 → 00001）")
    void nextNoFormatsPrefixDateAndFiveDigitSeq() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:nursing:seq:NR:" + today)).thenReturn(1L);

        NursingSeqGate gate = new NursingSeqGate(redisTemplate);
        assertThat(gate.nextNo("NR")).isEqualTo("NR" + today + "00001");
    }

    @Test
    @DisplayName("发号后对当日键续 48h TTL（键与 TTL 经 ArgumentCaptor 全量精确断言）")
    void nextNoAppliesTtlEachCall() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:nursing:seq:NR:" + today)).thenReturn(1L);

        NursingSeqGate gate = new NursingSeqGate(redisTemplate);
        gate.nextNo("NR");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate, times(1)).expire(keyCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("fy:nursing:seq:NR:" + today);
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    @DisplayName("未知业务号类型拒绝发号且不消费序号（IllegalArgumentException 含中文提示）")
    void nextNoRejectsUnknownType() {
        NursingSeqGate gate = new NursingSeqGate(redisTemplate);
        assertThatThrownBy(() -> gate.nextNo("XX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知业务号类型");
        // 拒绝路径不得触碰 Redis：无效类型不允许消耗任何序列号
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("序号位数：12345 恰五位不补零、100000 溢出进位不截断（防 %05d 丢位回归）")
    void nextNoPadsSeqToFiveDigits() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:nursing:seq:NR:" + today)).thenReturn(12345L, 100000L);

        NursingSeqGate gate = new NursingSeqGate(redisTemplate);
        assertThat(gate.nextNo("NR")).isEqualTo("NR" + today + "12345");
        assertThat(gate.nextNo("NR")).isEqualTo("NR" + today + "100000");
    }
}

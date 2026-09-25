package com.fuyun.inpatient.cache;

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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 住院业务号发号器单测：五类业务号（AD/MO/PL/CS/DC）与 visit_id 的格式冻结、独立键隔离、
 * 逐次 48h TTL 续期、流水递增与未知类型拒绝发号。
 *
 * <p>StringRedisTemplate 以 Mockito mock 承载（不起真实 Redis，NursingSeqGateTest 同款）；
 * 日期断言取服务器当日 {@code LocalDate.now()} 拼期望值（跨零点窗口的抖动概率可忽略）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientSeqGateTest {

    /** 与实现的键命名契约冻结：fy:inpatient:seq:{type}:{yyyyMMdd}（A.5-1 命名法） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("五类业务号发号：AD/MO/PL/CS/DC 均返回 前缀+当日 yyyyMMdd+五位序号（INCR=1 → 00001）")
    void nextNoFormatsAllFiveTypes() {
        String today = LocalDate.now().format(DAY);
        // 五类业务号逐类验证：键含类型段、返回值含类型前缀（例 MO2026092500001）
        for (String type : List.of("AD", "MO", "PL", "CS", "DC")) {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.increment("fy:inpatient:seq:" + type + ":" + today))
                    .thenReturn(1L);

            InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
            assertThat(gate.nextNo(type)).isEqualTo(type + today + "00001");
        }
    }

    @Test
    @DisplayName("visit_id 发号：独立键 VISIT + I 前缀 + 当日 + 五位序号（例 I2026092500001）")
    void nextVisitIdFormatsTypeIWithIndependentKey() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:inpatient:seq:VISIT:" + today)).thenReturn(1L);

        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        assertThat(gate.nextVisitId()).isEqualTo("I" + today + "00001");
    }

    @Test
    @DisplayName("流水递增：同日同键 INCR 返回 1/2 时序号逐次递增（00001 → 00002）")
    void nextNoIncrementsSequencePerDayKey() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:inpatient:seq:MO:" + today)).thenReturn(1L, 2L);

        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        assertThat(gate.nextNo("MO")).isEqualTo("MO" + today + "00001");
        assertThat(gate.nextNo("MO")).isEqualTo("MO" + today + "00002");
    }

    @Test
    @DisplayName("序号位数：12345 恰五位不补零、100000 溢出进位不截断（防 %05d 丢位回归）")
    void nextNoPadsSeqToFiveDigitsAndCarries() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:inpatient:seq:AD:" + today)).thenReturn(12345L, 100000L);

        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        assertThat(gate.nextNo("AD")).isEqualTo("AD" + today + "12345");
        assertThat(gate.nextNo("AD")).isEqualTo("AD" + today + "100000");
    }

    @Test
    @DisplayName("业务号发号后对当日键续 48h TTL（键与 TTL 经 ArgumentCaptor 全量精确断言）")
    void nextNoAppliesTtlEachCall() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:inpatient:seq:PL:" + today)).thenReturn(1L);

        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        gate.nextNo("PL");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate, times(1)).expire(keyCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("fy:inpatient:seq:PL:" + today);
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    @DisplayName("visit_id 发号后对独立 VISIT 键续 48h TTL（禁无 TTL 键）")
    void nextVisitIdAppliesTtlEachCall() {
        String today = LocalDate.now().format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:inpatient:seq:VISIT:" + today)).thenReturn(1L);

        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        gate.nextVisitId();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate, times(1)).expire(keyCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("fy:inpatient:seq:VISIT:" + today);
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    @DisplayName("未知业务号类型（含 VISIT 走错通道）拒绝发号且不消费序号（IllegalArgumentException）")
    void nextNoRejectsUnknownType() {
        InpatientSeqGate gate = new InpatientSeqGate(redisTemplate);
        assertThatThrownBy(() -> gate.nextNo("XX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知业务号类型");
        // visit_id 只能经 nextVisitId 独立签发（04 Spec 红线 1），VISIT 不得混入业务号白名单通道
        assertThatThrownBy(() -> gate.nextNo("VISIT")).isInstanceOf(IllegalArgumentException.class);
        // 拒绝路径不得触碰 Redis：无效类型不允许消耗任何序列号
        verify(valueOperations, never()).increment(anyString());
    }
}

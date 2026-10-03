package com.fuyun.pharmacy.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 药事域业务单号发号器单测（P2 PR-3 Task 8）：DP 五位/DPB 三位序号格式冻结、逐次 48h TTL
 * 续期、未知类型拒绝发号（NursingSeqGateTest 同款形态；日期断言取北京钟面当日）。
 */
@ExtendWith(MockitoExtension.class)
class PharmacySeqGateTest {

    /** 与实现的键命名契约冻结：fy:pharmacy:seq:{type}:{yyyyMMdd}（A.5-1 命名法，P2 PR-3 GC13 排定） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 北京钟面（时区纪律专项 B 类）：号段日期期望与生产技术日切同源口径，禁裸 now() */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("DP 类型发号：返回 前缀+当日 yyyyMMdd+五位序号（INCR=1 → 00001）")
    void nextNoFormatsPrefixDateAndFiveDigitSeq() {
        String today = LocalDate.now(BEIJING_TZ).format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:pharmacy:seq:DP:" + today)).thenReturn(1L);

        PharmacySeqGate gate = new PharmacySeqGate(redisTemplate);
        assertThat(gate.nextNo("DP")).isEqualTo("DP" + today + "00001");
    }

    @Test
    @DisplayName("DPB 排批号段发号：键 fy:pharmacy:seq:DPB:{yyyyMMdd} 同一命名法 + 三位序号（brief 3 位口径）")
    void nextNoIssuesPivasBatchNoOnDpbSegment() {
        String today = LocalDate.now(BEIJING_TZ).format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:pharmacy:seq:DPB:" + today)).thenReturn(12L);

        PharmacySeqGate gate = new PharmacySeqGate(redisTemplate);
        assertThat(gate.nextNo("DPB")).isEqualTo("DPB" + today + "012");
    }

    @Test
    @DisplayName("发号后对当日键续 48h TTL（键与 TTL 经 ArgumentCaptor 全量精确断言）")
    void nextNoAppliesTtlEachCall() {
        String today = LocalDate.now(BEIJING_TZ).format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:pharmacy:seq:DP:" + today)).thenReturn(1L);

        PharmacySeqGate gate = new PharmacySeqGate(redisTemplate);
        gate.nextNo("DP");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisTemplate, times(1)).expire(keyCaptor.capture(), ttlCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("fy:pharmacy:seq:DP:" + today);
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    @DisplayName("未知业务号类型拒绝发号且不消费序号（IllegalArgumentException 含中文提示）")
    void nextNoRejectsUnknownType() {
        PharmacySeqGate gate = new PharmacySeqGate(redisTemplate);
        assertThatThrownBy(() -> gate.nextNo("XX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知业务号类型");
        // 拒绝路径不得触碰 Redis：无效类型不允许消耗任何序列号
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("序号位数：DP 999 恰三位外补足五位、100000 溢出进位不截断（防 %05d 丢位回归）")
    void nextNoPadsSeqWithoutTruncation() {
        String today = LocalDate.now(BEIJING_TZ).format(DAY);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:pharmacy:seq:DP:" + today)).thenReturn(999L, 100000L);

        PharmacySeqGate gate = new PharmacySeqGate(redisTemplate);
        assertThat(gate.nextNo("DP")).isEqualTo("DP" + today + "00999");
        assertThat(gate.nextNo("DP")).isEqualTo("DP" + today + "100000");
    }
}

package com.fuyun.iot.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * IoT 业务号发号器单测：告警号 AL/命令号 CMD 两通道的键形态、序号拼装与当日键 TTL 续期
 * （Task 7 告警链消费 nextAlarmNo，Task 8 命令链消费 nextCommandNo）。
 * StringRedisTemplate 承载（禁 JDK 序列化）；INCR/EXPIRE 原子性归 Redis，本测只钉契约形态。
 */
@ExtendWith(MockitoExtension.class)
class IotSeqGateTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IotSeqGate gate;

    @BeforeEach
    void setUp() {
        gate = new IotSeqGate(redisTemplate);
    }

    @Test
    @DisplayName("nextAlarmNo：fy:iot:seq:AL:{yyyyMMdd} INCR 取号 → AL+日期+五位序号，并续 48h TTL")
    void nextAlarmNoIncrementsDailyKeyAndFormatsAlarmNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "fy:iot:seq:AL:" + day;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(7L);

        String alarmNo = gate.nextAlarmNo();

        assertThat(alarmNo).isEqualTo("AL" + day + "00007");
        // 每次自增后续 48h TTL：键生命周期完全由发号路径维护（次日自然换键归零）
        verify(redisTemplate).expire(key, Duration.ofHours(48));
    }

    @Test
    @DisplayName("nextCommandNo：fy:iot:seq:CMD:{yyyyMMdd} INCR 取号 → CMD+日期+五位序号（同形态独立通道）")
    void nextCommandNoUsesCommandKeyChannel() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "fy:iot:seq:CMD:" + day;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(99999L);

        String commandNo = gate.nextCommandNo();

        assertThat(commandNo).isEqualTo("CMD" + day + "99999");
        verify(redisTemplate).expire(key, Duration.ofHours(48));
    }
}

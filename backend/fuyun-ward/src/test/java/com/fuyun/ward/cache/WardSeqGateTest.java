package com.fuyun.ward.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 病房域业务号发号器单测（照 IotSeqGateTest 形态）：呼叫 CALL/冷链档案 ARCH/记录 CCR 三通道的
 * 键形态（fy:ward:seq: ward 域隔离）、序号拼装与当日键 TTL 续期。StringRedisTemplate 承载
 * （禁 JDK 序列化）；INCR/EXPIRE 原子性归 Redis，本测只钉契约形态。
 */
@ExtendWith(MockitoExtension.class)
class WardSeqGateTest {

    /** 北京钟面（时区纪律专项 B 类）：号段日期期望与生产技术日切同源口径，禁裸 now() */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private WardSeqGate gate;

    @BeforeEach
    void setUp() {
        gate = new WardSeqGate(redisTemplate);
    }

    @Test
    @DisplayName("nextCallNo：fy:ward:seq:CALL:{yyyyMMdd} INCR 取号 → CALL+日期+五位序号，并续 48h TTL")
    void nextCallNoIncrementsDailyKeyAndFormatsCallNo() {
        String day = LocalDate.now(BEIJING_TZ).format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "fy:ward:seq:CALL:" + day;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(7L);

        String callNo = gate.nextCallNo();

        assertThat(callNo).isEqualTo("CALL" + day + "00007");
        // 每次自增后续 48h TTL：键生命周期完全由发号路径维护（次日自然换键归零）
        verify(redisTemplate).expire(key, Duration.ofHours(48));
    }

    @Test
    @DisplayName("nextArchiveNo/nextRecordNo：ARCH/CCR 同形态独立通道（ward 域键空间隔离）")
    void archiveAndRecordChannelsUseOwnKeys() {
        String day = LocalDate.now(BEIJING_TZ).format(DateTimeFormatter.BASIC_ISO_DATE);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:ward:seq:ARCH:" + day)).thenReturn(1L);
        when(valueOperations.increment("fy:ward:seq:CCR:" + day)).thenReturn(99999L);

        assertThat(gate.nextArchiveNo()).isEqualTo("ARCH" + day + "00001");
        assertThat(gate.nextRecordNo()).isEqualTo("CCR" + day + "99999");
        verify(redisTemplate).expire("fy:ward:seq:ARCH:" + day, Duration.ofHours(48));
        verify(redisTemplate).expire("fy:ward:seq:CCR:" + day, Duration.ofHours(48));
    }
}

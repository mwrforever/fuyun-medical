package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.patient.api.VisitIdValidator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * visit_id 签发器单测（M03 CF-3 冻结结构 {@code O+yyyyMMdd+5 位流水}，Task 5 冻结用例集）：
 * 锚定三条红线——签发形态（14 位 O 型 + 结构自检为真）、当日首签续期 48h TTL（禁无过期键 A.5-1）、
 * 超 5 位日流水上限签发自检 fail-fast（IllegalStateException，违例即拒绝落库）。
 */
@ExtendWith(MockitoExtension.class)
class VisitIdIssuerImplTest {

    /** 签发日期段格式（yyyyMMdd，与 VisitIdValidator 日期段同源） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private VisitIdIssuerImpl issuer;

    @BeforeEach
    void setUp() {
        issuer = new VisitIdIssuerImpl(redisTemplate);
    }

    @Test
    @DisplayName("issue：INCR 返回 7 签发 O 型 14 位 visit_id（O+当日+00007）且 VisitIdValidator 自检为真")
    void issueProducesOTypeFourteenCharId() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:outpatient:visit-seq:" + today)).thenReturn(7L);

        String visitId = issuer.issue();

        assertThat(visitId).isEqualTo("O" + today + "00007");
        assertThat(visitId).hasSize(14);
        assertThat(VisitIdValidator.isValid(visitId)).isTrue();
    }

    @Test
    @DisplayName("issue：当日首签（seq=1）续期 48h TTL；seq=2 不再续期（禁无过期键，TTL 仅首签设置一次）")
    void issueSeqOneSetsTtlFortyEightHours() {
        String today = LocalDate.now().format(SEQ_DATE);
        String seqKey = "fy:outpatient:visit-seq:" + today;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 同日两次签发：INCR 依次返回 1、2（AtomicLong 模拟当日流水计数器）
        AtomicLong seq = new AtomicLong(0);
        when(valueOperations.increment(seqKey)).thenAnswer(inv -> seq.incrementAndGet());

        issuer.issue();
        issuer.issue();

        verify(redisTemplate, times(1)).expire(seqKey, Duration.ofHours(48));
    }

    @Test
    @DisplayName("issue：流水超 5 位日上限（100000）签发自检 fail-fast 抛 IllegalStateException，且不触碰 TTL")
    void issueFailsFastWhenSeqExceedsDailyCap() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:outpatient:visit-seq:" + today)).thenReturn(100000L);

        assertThatThrownBy(() -> issuer.issue()).isInstanceOf(IllegalStateException.class);
        verify(redisTemplate, never()).expire(any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("issue：Redis 流水返回空（连接异常面）fail-fast 抛 IllegalStateException，且不触碰 TTL")
    void issueFailsFastWhenRedisSeqMissing() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("fy:outpatient:visit-seq:" + today)).thenReturn(null);

        assertThatThrownBy(() -> issuer.issue()).isInstanceOf(IllegalStateException.class);
        verify(redisTemplate, never()).expire(any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("issue：结构自检防线（畸形流水致形态违例）fail-fast 抛 IllegalStateException——违例值禁落库")
    void issueFailsFastWhenStructureSelfCheckViolated() {
        String today = LocalDate.now().format(SEQ_DATE);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 负数流水使 %05d 产出畸形段（-0001），VisitIdValidator 结构自检必败——防御 CF-3 契约漂移的兜底分支
        when(valueOperations.increment("fy:outpatient:visit-seq:" + today)).thenReturn(-1L);

        assertThatThrownBy(() -> issuer.issue())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("结构自检失败");
        verify(redisTemplate, never()).expire(any(String.class), any(Duration.class));
    }
}

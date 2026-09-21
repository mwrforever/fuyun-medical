package com.fuyun.outpatient.service.impl;

import com.fuyun.outpatient.service.IVisitIdIssuer;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 就诊号签发器实现（CF-3 冻结结构 {@code O+yyyyMMdd+5 位流水}，Task 5）：当日流水经 Redis INCR
 * 当日键承载——键 {@code fy:outpatient:visit-seq:{yyyyMMdd}}（A.5-1 fy:{module}:{biz}:{id} 分层），
 * String 序列化（StringRedisTemplate），首签（INCR 返回 1）时设置 TTL=48h（裁决 11，禁无过期键），
 * 后续签发不重复续期（当日键自然覆盖跨日边界）。签发后 {@code VisitIdValidator.isValid} 结构自检，
 * 违例即 IllegalStateException fail-fast（红线：违例值禁落库）。装配归 OutpatientWebConfig @Import。
 * 线程安全：无状态单例（原子性由 Redis INCR 承载）。
 */
@Slf4j
public class VisitIdIssuerImpl implements IVisitIdIssuer {

    /** 签发流水键前缀（suffix=yyyyMMdd，A.5-1 冒号分层） */
    private static final String SEQ_KEY_PREFIX = "fy:outpatient:visit-seq:";

    /** 流水键 TTL（48h，裁决 11——跨日对账窗口缓冲，禁无过期键） */
    private static final Duration SEQ_KEY_TTL = Duration.ofHours(48);

    /** 签发日期段格式（yyyyMMdd，与 VisitIdValidator 日期段同源） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日流水 5 位上限（超限即签发自检 fail-fast） */
    private static final long DAILY_SEQ_CAP = 99999L;

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param redisTemplate Redis 字符串模板，非空；来源：Boot 自动装配（Key/Value 均 String 序列化）
     */
    public VisitIdIssuerImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 签发一枚当日 visit_id：{@code O + yyyyMMdd + String.format("%05d", seq)}。
     *
     * @return visit_id 原文（14 位），非空；结构自检通过方返回
     * @throws IllegalStateException 当日流水超 5 位上限（10 万号）或结构自检失败时触发；建议处理策略：
     *                               立即告警人工介入，禁止违例值落库
     */
    @Override
    public String issue() {
        String today = LocalDate.now().format(SEQ_DATE);
        String seqKey = SEQ_KEY_PREFIX + today;
        // 数据库写操作前置：Redis INCR 取当日流水（原子计数，跨实例并发安全）
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq == null) {
            throw new IllegalStateException("visit_id 签发失败：Redis 流水返回空，seqKey=" + seqKey);
        }
        if (seq == 1L) {
            // 首签续期 48h TTL（禁无过期键；后续签发不重复设置，保持 TTL 单次语义）
            redisTemplate.expire(seqKey, SEQ_KEY_TTL);
        }
        if (seq > DAILY_SEQ_CAP) {
            throw new IllegalStateException("visit_id 签发失败：当日流水超 5 位上限（seq=" + seq + "），seqKey=" + seqKey);
        }
        String visitId = "O" + today + String.format("%05d", seq);
        if (!VisitIdValidator.isValid(visitId)) {
            // 结构红线 fail-fast：违例值禁落库（理论不可达，防御 CF-3 契约漂移）
            throw new IllegalStateException("visit_id 签发结构自检失败：visitId=" + visitId);
        }
        log.info("visit_id 已签发：visitId={}", visitId);
        return visitId;
    }
}

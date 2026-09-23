package com.fuyun.nursing.cache;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 护理业务单号发号器（住院单 NR/评估单 AS/任务单 TK/交接班单 HO 四类业务号的统一取号出口）。
 *
 * <p>键 {@code fy:nursing:seq:{type}:{yyyyMMdd}}（A.5-1 命名），Redis INCR 原子自增取号后格式化
 * 为 {@code type + yyyyMMdd + %05d}（例 NR2026092200001）；每次自增后对当日键续 48h TTL——次日
 * 自然换键归零，48h 覆盖跨日重叠请求窗口。INCR 与 EXPIRE 均为单命令原子操作（PR-6 计划
 * Global Constraints 15），无需 Lua 脚本；多实例并发取号由 Redis 单线程命令串行保证不重号。
 * StringRedisTemplate 承载（照 PatientCacheService 注入形态，禁 JDK 序列化）；
 * 无状态单例（装配归 NursingConfig，随 Task 2/3 接线）。
 */
public class NursingSeqGate {

    /** 键前缀：fy:nursing:seq: */
    private static final String KEY_PREFIX = "fy:nursing:seq:";

    /** 日期段格式：yyyyMMdd（BasicIsoDate） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日键 TTL：48 小时（覆盖跨日重叠请求窗口，过期由 Redis 兜底免定时清理） */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    /** 合法业务号类型白名单（NR 住院单/AS 评估单/TK 任务单/HO 交接班单） */
    private static final Set<String> TYPES = Set.of("NR", "AS", "TK", "HO");

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 NursingConfig @Import，Task 2/3 接线）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     */
    public NursingSeqGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取下一业务单号（类型助记 + 当日 + 五位日内序号）。
     *
     * @param type 业务号类型：NR/AS/TK/HO 四值之一，非空
     * @return 形如 NR2026092200001 的业务单号，非空；日内序号超 99999 时自然进位不截断
     * @throws IllegalArgumentException type 不在四值白名单内（编程错误，fail-fast，不触碰 Redis）
     */
    public String nextNo(String type) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("未知业务号类型：" + type);
        }
        String day = LocalDate.now().format(DAY);
        String key = KEY_PREFIX + type + ":" + day;
        // Redis INCR 原子自增取号：多实例并发不重号（单命令原子，禁 Lua/额外锁——计划 GC15）
        Long seq = redisTemplate.opsForValue().increment(key);
        // 每次自增后对当日键续 48h TTL：单命令原子，键生命周期完全由发号路径维护
        redisTemplate.expire(key, KEY_TTL);
        // %05d 五位右补零；seq ≥ 100000 时格式化自然扩位（如 100000）不丢位
        return type + day + String.format("%05d", seq);
    }
}

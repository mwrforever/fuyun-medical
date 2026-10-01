package com.fuyun.iot.cache;

import com.fuyun.common.constants.TimeConstants;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * IoT 业务号发号器（告警号 AL、命令号 CMD 与联动号 LG 的统一取号出口，形态照 InpatientSeqGate）。
 *
 * <p>号键 {@code fy:iot:seq:{AL|CMD|LG}:{yyyyMMdd}}（A.5-1 命名），Redis INCR 原子自增取号后格式化为
 * {@code 前缀 + yyyyMMdd + %05d}（例 AL2026092600001）；每次自增后对当日键续 48h TTL——次日自然
 * 换键归零，48h 覆盖跨日重叠请求窗口。INCR 与 EXPIRE 均为单命令原子操作（计划 GC13），无需 Lua
 * 脚本；多实例并发取号由 Redis 单线程命令串行保证不重号。StringRedisTemplate 承载（禁 JDK
 * 序列化）；无状态单例（装配归 IotConfig，告警链 Task 7 消费 nextAlarmNo、命令链 Task 8 消费
 * nextCommandNo、联动链 Task 9 消费 nextLinkageNo）。
 */
public class IotSeqGate {

    /** 键前缀：fy:iot:seq: */
    private static final String KEY_PREFIX = "fy:iot:seq:";

    /** 告警号键段与单号前缀：AL */
    private static final String ALARM_TYPE = "AL";

    /** 命令号键段与单号前缀：CMD */
    private static final String COMMAND_TYPE = "CMD";

    /** 联动执行号键段与单号前缀：LG（P2 PR-2 Task 9 同款形态扩展） */
    private static final String LINKAGE_TYPE = "LG";

    /** 日期段格式：yyyyMMdd（BasicIsoDate） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日键 TTL：48 小时（覆盖跨日重叠请求窗口，过期由 Redis 兜底免定时清理） */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     */
    public IotSeqGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取下一告警号（Task 7 告警触发链消费）。
     *
     * @return 形如 AL2026092600001 的告警号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextAlarmNo() {
        return next(ALARM_TYPE);
    }

    /**
     * 取下一命令号（Task 8 命令下发链消费）。
     *
     * @return 形如 CMD2026092600001 的命令号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextCommandNo() {
        return next(COMMAND_TYPE);
    }

    /**
     * 取下一联动执行号（Task 9 联动执行链消费，单号前缀 LG 同款形态扩展）。
     *
     * @return 形如 LG2026092600001 的联动号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextLinkageNo() {
        return next(LINKAGE_TYPE);
    }

    /**
     * 发号共用实现：对当日键 INCR 取号后续 48h TTL 并拼装序号。
     *
     * @param type 号类型键段兼单号前缀（AL/CMD），非空
     * @return type + yyyyMMdd + %05d 五位右补零序号；序号 ≥ 100000 时自然扩位不丢位
     */
    private String next(String type) {
        // 日期段单次采样：键与单号共用同一天，规避跨零点窗口键/号日期错位；技术日切取北京钟面
        // （时区纪律专项 B 类），号段不随容器时区提前/延后 8 小时翻段
        String day = LocalDate.now(TimeConstants.HEALTHCARE_TZ).format(DAY);
        String key = KEY_PREFIX + type + ":" + day;
        // Redis INCR 原子自增取号：多实例并发不重号（单命令原子，禁 Lua/额外锁——计划 GC13）
        Long seq = redisTemplate.opsForValue().increment(key);
        // 每次自增后对当日键续 48h TTL：单命令原子，键生命周期完全由发号路径维护
        redisTemplate.expire(key, KEY_TTL);
        return type + day + String.format("%05d", seq);
    }
}

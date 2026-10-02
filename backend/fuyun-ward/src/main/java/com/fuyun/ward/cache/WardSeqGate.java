package com.fuyun.ward.cache;

import com.fuyun.common.constants.TimeConstants;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 病房域业务号发号器（呼叫 CALL 与冷链档案 ARCH/记录 CCR 的统一取号出口，形态照 IotSeqGate）。
 *
 * <p>号键 {@code fy:ward:seq:{CALL|ARCH|CCR}:{yyyyMMdd}}（A.5-1 命名，ward 域隔离——与
 * fy:iot:seq: 等其他域键空间互斥），Redis INCR 原子自增取号后格式化为 {@code 前缀 + yyyyMMdd +
 * %05d}（例 CALL2026092600001）；每次自增后对当日键续 48h TTL——次日自然换键归零，48h 覆盖
 * 跨日重叠请求窗口。INCR 与 EXPIRE 均为单命令原子操作（计划 GC15），无需 Lua 脚本；多实例并发
 * 取号由 Redis 单线程命令串行保证不重号。StringRedisTemplate 承载（禁 JDK 序列化）；无状态
 * 单例（装配归 WardWebConfig，呼叫链 Task 12 消费 nextCallNo、冷链域同任务消费 nextArchiveNo/
 * nextRecordNo——档案/记录号前缀 brief 未冻结，照 CALL 同款形态扩展申报）。
 */
public class WardSeqGate {

    /** 键前缀：fy:ward:seq:（WardMessagingConstants 同源，发号器自持避免常量反向依赖） */
    private static final String KEY_PREFIX = "fy:ward:seq:";

    /** 呼叫号键段与单号前缀：CALL */
    private static final String CALL_TYPE = "CALL";

    /** 冷链档案号键段与单号前缀：ARCH */
    private static final String ARCHIVE_TYPE = "ARCH";

    /** 冷链记录号键段与单号前缀：CCR */
    private static final String RECORD_TYPE = "CCR";

    /** 日期段格式：yyyyMMdd（BasicIsoDate） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日键 TTL：48 小时（覆盖跨日重叠请求窗口，过期由 Redis 兜底免定时清理） */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 WardWebConfig @Import，backend 宪法 B.1）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     */
    public WardSeqGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取下一呼叫号（呼叫状态机创建链消费）。
     *
     * @return 形如 CALL2026092600001 的呼叫号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextCallNo() {
        return next(CALL_TYPE);
    }

    /**
     * 取下一冷链档案号（冷链档案建档链消费）。
     *
     * @return 形如 ARCH2026092600001 的档案号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextArchiveNo() {
        return next(ARCHIVE_TYPE);
    }

    /**
     * 取下一冷链记录号（冷链记录登记链消费）。
     *
     * @return 形如 CCR2026092600001 的记录号，非空；日内序号超 99999 时自然进位不截断
     */
    public String nextRecordNo() {
        return next(RECORD_TYPE);
    }

    /**
     * 发号共用实现：对当日键 INCR 取号后续 48h TTL 并拼装序号。
     *
     * @param type 号类型键段兼单号前缀（CALL/ARCH/CCR），非空
     * @return type + yyyyMMdd + %05d 五位右补零序号；序号 ≥ 100000 时自然扩位不丢位
     */
    private String next(String type) {
        // 日期段单次采样：键与单号共用同一天，规避跨零点窗口键/号日期错位；技术日切取北京钟面
        // （时区纪律专项 B 类），号段不随容器时区提前/延后 8 小时翻段
        String day = LocalDate.now(TimeConstants.HEALTHCARE_TZ).format(DAY);
        String key = KEY_PREFIX + type + ":" + day;
        // Redis INCR 原子自增取号：多实例并发不重号（单命令原子，禁 Lua/额外锁——计划 GC15）
        Long seq = redisTemplate.opsForValue().increment(key);
        // 每次自增后对当日键续 48h TTL：单命令原子，键生命周期完全由发号路径维护
        redisTemplate.expire(key, KEY_TTL);
        return type + day + String.format("%05d", seq);
    }
}

package com.fuyun.pharmacy.cache;

import com.fuyun.common.constants.TimeConstants;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 药事域业务单号发号器（摆药计划号 DP 五位 / PIVAS 排批号 DPB 三位两类业务号的统一取号出口，
 * NursingSeqGate 同款形态）。键 {@code fy:pharmacy:seq:{type}:{yyyyMMdd}}（A.5-1 命名，
 * P2 PR-3 GC13 排定），Redis INCR 原子自增取号后格式化为 {@code type + yyyyMMdd + 序号}
 * （例 DP2026100200001 / DPB20261002001）；每次自增后对当日键续 48h TTL——次日自然换键归零，
 * 48h 覆盖跨日重叠请求窗口。INCR 与 EXPIRE 均为单命令原子操作，无需 Lua 脚本；多实例并发
 * 取号由 Redis 单线程命令串行保证不重号。日期段取北京钟面（时区红线——业务号日期不随容器
 * 时区漂移）。无状态单例（装配归 PharmacyWebConfig @Import，P2 PR-3 Task 8 接线）。
 */
public class PharmacySeqGate {

    /** 键前缀：fy:pharmacy:seq: */
    private static final String KEY_PREFIX = "fy:pharmacy:seq:";

    /** 日期段格式：yyyyMMdd（BasicIsoDate） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日键 TTL：48 小时（覆盖跨日重叠请求窗口，过期由 Redis 兜底免定时清理） */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    /** 合法业务号类型白名单（DP 摆药计划号 / DPB PIVAS 排批号——序号宽度各异见 {@link #nextNo}） */
    private static final Set<String> TYPES = Set.of("DP", "DPB");

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 PharmacyWebConfig @Import）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     */
    public PharmacySeqGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取下一业务单号（类型助记 + 北京钟面当日 + 日内序号；DP 五位右补零、DPB 三位右补零——
     * DPB 为排批短号，给药时点分批日内窗口三位容量足够，超限自然进位不截断）。
     *
     * @param type 业务号类型：DP/DPB 两值之一，非空
     * @return 形如 DP2026100200001 / DPB20261002001 的业务单号，非空
     * @throws IllegalArgumentException type 不在两值白名单内（编程错误 fail-fast，不触碰 Redis）
     */
    public String nextNo(String type) {
        if (!TYPES.contains(type)) {
            // EX-19 C 类收口留痕：内部断言（生产调用点全部传 DP/DPB 字面量，编程错误 fail-fast）
            throw new IllegalArgumentException("未知业务号类型：" + type);
        }
        // 技术日切取北京钟面（时区纪律专项 B 类）：键与单号日期段不随容器时区漂移
        String day = LocalDate.now(TimeConstants.HEALTHCARE_TZ).format(DAY);
        String key = KEY_PREFIX + type + ":" + day;
        // Redis INCR 原子自增取号：多实例并发不重号（单命令原子，禁 Lua/额外锁）
        Long seq = redisTemplate.opsForValue().increment(key);
        // 每次自增后对当日键续 48h TTL：单命令原子，键生命周期完全由发号路径维护
        redisTemplate.expire(key, KEY_TTL);
        int width = "DPB".equals(type) ? 3 : 5;
        // 右补零；seq 超出宽度时格式化自然扩位不丢位
        return type + day + String.format("%0" + width + "d", seq);
    }
}

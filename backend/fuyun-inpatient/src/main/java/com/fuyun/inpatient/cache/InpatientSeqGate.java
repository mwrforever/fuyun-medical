package com.fuyun.inpatient.cache;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 住院业务号发号器（住院证 AD/医嘱号 MO/执行计划 PL/会诊 CS/出院申请 DC 五类业务号与
 * I 型 visit_id 的统一取号出口）。
 *
 * <p>业务号键 {@code fy:inpatient:seq:{type}:{yyyyMMdd}}（A.5-1 命名），Redis INCR 原子自增取号后
 * 格式化为 {@code type + yyyyMMdd + %05d}（例 MO2026092500001）；visit_id 走独立键
 * {@code fy:inpatient:seq:VISIT:{yyyyMMdd}}，签发 {@code I + yyyyMMdd + %05d}（类型码 I + 8 位
 * 日期 + 5 位当日流水，定长 14 位，M02 结构规范——04 Spec 红线 1：visit_id 只能由本方法族签发，
 * 入院登记确认事务内与落库同事务）；每次自增后对当日键续 48h TTL——次日自然换键归零，48h 覆盖
 * 跨日重叠请求窗口。INCR 与 EXPIRE 均为单命令原子操作（P2 PR-1 计划 GC15），无需 Lua 脚本；
 * 多实例并发取号由 Redis 单线程命令串行保证不重号（visit_id 唯一性另由 DB 唯一约束兜底）。
 * StringRedisTemplate 承载（照 NursingSeqGate 注入形态，禁 JDK 序列化）；
 * 无状态单例（装配归 InpatientConfig，随 Task 2/3 接线）。
 */
public class InpatientSeqGate {

    /** 键前缀：fy:inpatient:seq: */
    private static final String KEY_PREFIX = "fy:inpatient:seq:";

    /** visit_id 独立键段：VISIT（不进业务号白名单——visit_id 只经 nextVisitId 专用通道签发） */
    private static final String VISIT_KEY_TYPE = "VISIT";

    /** visit_id 类型码：I（M02 结构规范，定长 14 位） */
    private static final String VISIT_ID_PREFIX = "I";

    /** 日期段格式：yyyyMMdd（BasicIsoDate） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 当日键 TTL：48 小时（覆盖跨日重叠请求窗口，过期由 Redis 兜底免定时清理） */
    private static final Duration KEY_TTL = Duration.ofHours(48);

    /** 合法业务号类型白名单（AD 住院证/MO 医嘱号/PL 执行计划/CS 会诊/DC 出院申请） */
    private static final Set<String> TYPES = Set.of("AD", "MO", "PL", "CS", "DC");

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 InpatientConfig @Import，Task 2/3 接线）。
     *
     * @param redisTemplate String 模板（A.5-1），非空
     */
    public InpatientSeqGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 取下一业务单号（类型助记 + 当日 + 五位日内序号）。
     *
     * @param type 业务号类型：AD/MO/PL/CS/DC 五值之一，非空（visit_id 不得走本通道）
     * @return 形如 MO2026092500001 的业务单号，非空；日内序号超 99999 时自然进位不截断
     * @throws IllegalArgumentException type 不在五值白名单内（编程错误，fail-fast，不触碰 Redis）
     */
    public String nextNo(String type) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("未知业务号类型：" + type);
        }
        // 日期段单次采样：键与单号共用同一天，规避跨零点窗口键/号日期错位
        String day = day();
        return next(KEY_PREFIX + type + ":" + day, type, day);
    }

    /**
     * 取下一 I 型住院就诊号（visit_id）——独立键独立通道，入院登记确认事务内调用。
     *
     * @return 形如 I2026092500001 的 14 位 visit_id，非空；结构经 VisitIdValidator 自证
     */
    public String nextVisitId() {
        // 日期段单次采样：同 nextNo，键与 visit_id 共用同一天
        String day = day();
        return next(KEY_PREFIX + VISIT_KEY_TYPE + ":" + day, VISIT_ID_PREFIX, day);
    }

    /** 取服务器当日 yyyyMMdd 日期段。 */
    private String day() {
        return LocalDate.now().format(DAY);
    }

    /**
     * 发号共用实现：对当日键 INCR 取号后续 48h TTL 并拼装序号。
     *
     * @param key 当日流水键（fy:inpatient:seq:{键段}:{yyyyMMdd}），非空
     * @param prefix 单号类型前缀（五类业务号助记或 visit_id 类型码 I），非空
     * @param day 日期段 yyyyMMdd（与 key 内日期同源单次采样），非空
     * @return prefix + yyyyMMdd + %05d 五位右补零序号；序号 ≥ 100000 时自然扩位不丢位
     */
    private String next(String key, String prefix, String day) {
        // Redis INCR 原子自增取号：多实例并发不重号（单命令原子，禁 Lua/额外锁——计划 GC15）
        Long seq = redisTemplate.opsForValue().increment(key);
        // 每次自增后对当日键续 48h TTL：单命令原子，键生命周期完全由发号路径维护
        redisTemplate.expire(key, KEY_TTL);
        return prefix + day + String.format("%05d", seq);
    }
}

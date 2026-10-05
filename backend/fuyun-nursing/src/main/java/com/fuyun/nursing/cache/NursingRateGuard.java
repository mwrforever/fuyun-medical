package com.fuyun.nursing.cache;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/**
 * 护理域频控守卫（PR-4E Task 6，A-6 不良事件上报限频 + A-8 PDA 枚举探测冷却）：
 * 以 Redis INCR+EXPIRE 承载两类效率层防线——
 * <ul>
 *   <li>A-6 上报限频（窗口计数族 {@link #checkWithinWindow}）：每操作者固定窗口内上报
 *       次数上限（10 次/分钟），超阈拒绝——防脚本批量刷单污染不良事件统计与 id 83 事件面。</li>
 *   <li>A-8 PDA 枚举冷却（冷却族 {@link #checkNotCooling} / {@link #recordProbeFailure} /
 *       {@link #clearFailureCount}）：PDA 标识解析端点可被用于枚举探测他人证件号（反复试错
 *       未命中标识），「同一标识计数窗口内连续解析失败达阈值 → 冷却期内拒绝受理」（判定面
 *       集中形态，PortalCredentialRateGuard 同款）。</li>
 * </ul>
 *
 * <p>键规范（A.5-1 冒号分层；键成分统一取 {@code 业务段:键成分} 的 SHA-256 hex 摘要——
 * PDA identifier 含证件号属敏感字段明文禁入 Redis 键；operatorId 虽非敏感也统一摘要，
 * 简单一致且日志以摘要前 8 位留痕锚）：
 * <ul>
 *   <li>计数键 {@code fy:nursing:{space}:{digest}}——窗口计数（首次计数置 TTL 固定窗口）与
 *       失败计数（每次失败续期 TTL，StormGuard 自愈续期同款，杜绝进程异常遗留永久键）共用
 *       形态，业务段隔离互不冲突；解析成功即删除失败计数键（「连续」语义：成功打断计数）。</li>
 *   <li>冷却标记键 {@code fy:nursing:{space}-cool:{digest}}——达阈值时置位，TTL=冷却时长，
 *       到期自然解除。</li>
 * </ul>
 *
 * <p>窗口不变式（A-8）：冷却时长（30m）&gt; 失败计数窗口（10m），冷却期内调用方在解析前即被拒
 * （不再累计），冷却结束时计数键必然已先过期——冷却解除即全新计数窗口，杜绝「旧计数续期冷却」
 * 的永久封锁。
 *
 * <p>Redis 降级语义（StormGuard 同款）：频控是效率层防线而非正确性防线，Redis 异常一律降级
 * 放行/跳过计数（warn 中文留痕，不阻断医护主链路可用性）；盒装返回值（increment 可能 null）
 * 一律判空。
 *
 * <p>线程安全：无状态单例（Redis 承载全部窗口状态，多实例共享判定一致）；装配归
 * NursingWebConfig @Import（backend 宪法 B.1）。
 */
@Slf4j
public class NursingRateGuard {

    /** 计数键前缀基座：fy:nursing:（拼 {space} 业务段 + 摘要成分，A.5-1 冒号分层） */
    private static final String KEY_PREFIX = "fy:nursing:";

    /** 冷却标记键业务段后缀（拼在 space 之后：fy:nursing:{space}-cool:{digest}） */
    private static final String COOL_SPACE_SUFFIX = "-cool";

    /** A-6 上报限频：每操作者窗口内上报次数上限（10 次——正常护士单分钟内不可能连续上报 10 单） */
    public static final int REPORT_LIMIT = 10;

    /** A-6 上报限频：计数窗口 60 秒（固定窗口，首计置 TTL 到期整窗重置） */
    public static final long REPORT_WINDOW_MS = 60_000L;

    /** A-8 PDA 枚举探测：连续解析失败阈值（5 次——PortalCredentialRateGuard 同锚，
     * 连续 5 次未命中属枚举探测形态，正常护士偶发输错 1~2 次即纠正） */
    public static final int PDA_THRESHOLD = 5;

    /** A-8 PDA 枚举探测：冷却时长 30 分钟（与全仓试错锁定时长同锚） */
    public static final long PDA_COOL_MS = 30 * 60_000L;

    /** A-8 PDA 枚举探测：失败计数窗口 10 分钟——须短于冷却时长（窗口不变式，见类 javadoc）；
     * 窗口内「连续」失败才累计，跨窗口偶发输错不受影响 */
    private static final long PDA_COUNT_WINDOW_MS = 10 * 60_000L;

    /** 摘要算法名（JDK 标准名） */
    private static final String DIGEST_ALGORITHM_SHA256 = "SHA-256";

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param redisTemplate Redis 字符串模板（A.5-1），非空；窗口计数与冷却标记的承载通道
     */
    public NursingRateGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 窗口计数判定（A-6 上报限频原语）：INCR 计数 + 首次计数置窗口 TTL（固定窗口——非首计
     * 不续期，到期整窗重置），计数 ≤ 上限放行 true / 超阈 false；Redis 异常降级放行 true
     * （warn 留痕，频控缺失不阻断医护主链路，StormGuard 同款）。
     *
     * @param space    业务段（如 report-freq——计数键的业务隔离成分），非空
     * @param key      计数键成分（如操作者标识），非空；统一内部摘要，明文禁入 Redis 键
     * @param limit    窗口内次数上限，正整数；来源：调用方常量（A-6 为 REPORT_LIMIT=10）
     * @param windowMs 窗口时长（毫秒），正数；来源：调用方常量（A-6 为 REPORT_WINDOW_MS=60_000）
     * @return true=窗口内未超限放行 / false=超阈拒绝；Redis 异常降级恒 true
     */
    public boolean checkWithinWindow(String space, String key, int limit, long windowMs) {
        String countKey = countKey(space, key);
        try {
            // 缓存写操作：Redis INCR 原子计数（窗口内首次计数返回 1；盒装判空防降级场景 NPE）
            Long count = redisTemplate.opsForValue().increment(countKey);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                // 缓存写操作：仅首次计数设置窗口 TTL（固定窗口语义；禁无过期键）
                redisTemplate.expire(countKey, Duration.ofMillis(windowMs));
            }
            return count <= limit;
        } catch (RuntimeException e) {
            // Redis 降级：频控为效率层防线，计数失败放行（warn 留痕，不阻断上报主链路可用性）
            log.warn("护理频控窗口计数 Redis 异常（降级放行）：space={}，原因={}", space, e.getMessage());
            return true;
        }
    }

    /**
     * 冷却期前置判定（A-8 PDA 标识解析第一步，判定面集中形态）：冷却标记在挂即抛 NS-1030 429
     * 拒绝，阻断冷却期内的枚举试错触达标识解析；Redis 异常降级放行（频控缺失不阻断主链路）。
     *
     * @param space 业务段（pda-probe——冷却族共用业务隔离成分），非空
     * @param key   键成分（PDA 扫码标识——含证件号形态），非空；仅本调用生命周期内存活，禁入日志
     * @throws BizException NS-1030（429 PDA 标识解析失败频控冷却中）时触发；建议处理策略：提示
     *                      稍后重试，冷却到期自动恢复
     */
    public void checkNotCooling(String space, String key) {
        boolean cooling;
        try {
            // 缓存读操作：冷却标记存在性查询（键成分为摘要，不含标识明文）
            cooling = Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(space, key)));
        } catch (RuntimeException e) {
            // Redis 降级：频控为辅助语义，查询失败放行（warn 留痕，不阻断 PDA 查询可用性）
            log.warn("护理频控冷却查询 Redis 异常（降级放行）：space={}，原因={}", space, e.getMessage());
            return;
        }
        if (cooling) {
            // 日志禁打印标识明文（患者敏感字段脱敏红线），以业务段+摘要前 8 位留痕锚定
            log.warn("PDA 标识解析失败达阈值，限流冷却中拒绝受理：space={}，键摘要前8位={}", space, digestPrefix(space, key));
            throw new BizException(
                    NursingErrorCode.PDA_PROBE_COOLING, HttpStatus.TOO_MANY_REQUESTS, "PDA 标识解析失败次数过多，已进入限流冷却，请稍后重试");
        }
    }

    /**
     * 解析失败计数（A-8：resolveByIdentifier 抛 BizException 后调用——仅卡号/证件号解析路径）：
     * 失败计数 INCR+窗口续期，连续失败达阈值置冷却标记（TTL=冷却时长，到期自然解除）；
     * Redis 异常降级跳过（warn 留痕，本窗口少计一次下次失败自愈续期）。
     *
     * @param space     业务段（pda-probe），非空
     * @param key       键成分（PDA 扫码标识），非空；禁入日志
     * @param threshold 连续失败阈值，正整数；来源：调用方常量（A-8 为 PDA_THRESHOLD=5）
     * @param coolMs    冷却时长（毫秒），正数且须大于失败计数窗口（窗口不变式）；
     *                  来源：调用方常量（A-8 为 PDA_COOL_MS=30 分钟）
     */
    public void recordProbeFailure(String space, String key, int threshold, long coolMs) {
        String failKey = countKey(space, key);
        try {
            // 缓存写操作：Redis INCR 原子计数（窗口内首次失败返回 1；盒装判空防降级场景 NPE）
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count == null) {
                return;
            }
            // 缓存写操作：每次失败重续窗口 TTL（StormGuard 同款自愈续期，禁无过期键）
            redisTemplate.expire(failKey, Duration.ofMillis(PDA_COUNT_WINDOW_MS));
            if (count >= threshold) {
                // 达阈值置冷却标记（TTL=冷却时长；冷却>窗口的不变式保证冷却结束即全新计数窗口）
                redisTemplate.opsForValue().set(cooldownKey(space, key), "1", Duration.ofMillis(coolMs));
                log.warn(
                        "PDA 标识连续解析失败达阈值，进入限流冷却：space={}，连续失败={}，阈值={}，冷却分钟数={}",
                        space,
                        count,
                        threshold,
                        Duration.ofMillis(coolMs).toMinutes());
            }
        } catch (RuntimeException e) {
            // Redis 降级：计数失败仅留痕（本窗口少计一次，下次失败自愈续期）
            log.warn("护理频控失败计数 Redis 操作失败（降级跳过）：space={}，原因={}", space, e.getMessage());
        }
    }

    /**
     * 解析成功清零计数（A-8「连续失败」语义的成功打断面）：删除失败计数键；冷却标记不动
     * （已进入冷却即完整执行冷却期，杜绝临界反复）；Redis 异常降级跳过（窗口 TTL 兜底自然过期）。
     *
     * @param space 业务段（pda-probe），非空
     * @param key   键成分（PDA 扫码标识），非空；禁入日志
     */
    public void clearFailureCount(String space, String key) {
        try {
            // 缓存写操作：删除失败计数键（成功打断连续计数）
            redisTemplate.delete(countKey(space, key));
        } catch (RuntimeException e) {
            // Redis 降级：清零失败仅留痕（计数键窗口 TTL 到期自然归零）
            log.warn("护理频控计数清零 Redis 异常（窗口 TTL 兜底）：space={}，原因={}", space, e.getMessage());
        }
    }

    /**
     * 计数键拼装：fy:nursing:{space}:{SHA-256(space:key)}（窗口计数与失败计数共用形态，
     * 业务段隔离互不冲突）。
     *
     * @param space 业务段，非空
     * @param key   键成分，非空
     * @return 计数键文本，非空；不含键成分明文
     */
    private static String countKey(String space, String key) {
        return KEY_PREFIX + space + ":" + digest(space, key);
    }

    /**
     * 冷却标记键拼装：fy:nursing:{space}-cool:{SHA-256(space:key)}（摘要成分与计数键同源，
     * 仅业务段后缀区分键族）。
     *
     * @param space 业务段，非空
     * @param key   键成分，非空
     * @return 冷却标记键文本，非空；不含键成分明文
     */
    private static String cooldownKey(String space, String key) {
        return KEY_PREFIX + space + COOL_SPACE_SUFFIX + ":" + digest(space, key);
    }

    /**
     * 键成分摘要前 8 位（日志留痕锚点：不可逆短前缀，兼顾排障定位与脱敏）。
     *
     * @param space 业务段，非空
     * @param key   键成分，非空
     * @return 摘要前 8 位文本，非空
     */
    private static String digestPrefix(String space, String key) {
        return digest(space, key).substring(0, 8);
    }

    /**
     * 计算 {@code 业务段:键成分} 的 SHA-256 小写 hex 摘要（64 位；Redis 键成分——标识/操作者
     * 明文禁入键，PortalCredentialRateGuard 同款形态）。
     *
     * @param space 业务段，非空
     * @param key   键成分，非空
     * @return 64 位小写十六进制摘要，非空
     * @throws IllegalStateException SHA-256 算法不可用（JDK 环境异常，理论不可达；环境级防御
     *                               断言，走全局 500 兜底，不转业务错误码）
     */
    private static String digest(String space, String key) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256);
            byte[] hashed = messageDigest.digest((space + ":" + key).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("护理频控摘要算法不可用（JDK 环境异常）", e);
        }
    }
}

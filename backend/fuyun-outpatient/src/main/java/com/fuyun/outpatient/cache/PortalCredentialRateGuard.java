package com.fuyun.outpatient.cache;

import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/**
 * portal 免登录预约证件号频控守卫（EX-29 临时缓解②，BE-A3-02 裁决③转正项）：匿名预约端点可被
 * 用于枚举探测他人证件号（提交与档案不符的介质信息反复试错），本守卫以 Redis INCR+EXPIRE 承载
 * 「同一证件号计数窗口内连续解析失败达阈值 → 冷却期内拒绝受理」的判定执行点（StormGuard 同款
 * 判定面集中形态）。
 *
 * <p>键规范（A.5-1 冒号分层；证件号明文禁入 Redis 键——患者敏感字段脱敏红线，键成分取
 * {@code 介质类型:介质号} 的 SHA-256 hex 摘要，DeadLetterListener 同款摘要形态；patient 库内
 * 等值检索列的 HMAC 盲索引红线针对可长期留存的关系列，本键为短 TTL 计数器不属该域）：
 * <ul>
 *   <li>失败计数键 {@code fy:outpatient:portal-cred-fail:{digest}}——TTL=计数窗口，每次失败续期
 *       （StormGuard 自愈续期同款，杜绝进程异常遗留永久键）；解析成功即删除（「连续」语义：成功
 *       打断计数）。</li>
 *   <li>冷却标记键 {@code fy:outpatient:portal-cred-cool:{digest}}——达阈值时置位，TTL=冷却时长，
 *       到期自然解除。</li>
 * </ul>
 *
 * <p>窗口不变式：冷却时长（30m）&gt; 计数窗口（10m），冷却期内调用方在解析前即被拒（不再累计），
 * 冷却结束时计数键必然已先过期——冷却解除即全新计数窗口，杜绝「旧计数续期冷却」的永久封锁。
 *
 * <p>Redis 降级语义（StormGuard 同款）：频控为辅助缓解语义，Redis 异常降级放行/跳过计数
 * （warn 留痕，不阻断匿名预约主链路可用性）；盒装返回值（increment 可能 null）一律判空。
 * 临时缓解：M18 患者账号体系上线后由归属校验取代（BE-A3-02，裁决③）。
 *
 * <p>线程安全：无状态单例（Redis 承载全部窗口状态，多实例共享判定一致）；装配归
 * OutpatientWebConfig @Import（backend 宪法 B.1）。
 */
@Slf4j
public class PortalCredentialRateGuard {

    /** 失败计数键前缀：fy:outpatient:portal-cred-fail:（拼 SHA-256 摘要成分） */
    private static final String FAIL_COUNT_KEY_PREFIX = "fy:outpatient:portal-cred-fail:";

    /** 冷却标记键前缀：fy:outpatient:portal-cred-cool:（拼 SHA-256 摘要成分） */
    private static final String COOLDOWN_KEY_PREFIX = "fy:outpatient:portal-cred-cool:";

    /** 连续解析失败阈值：5 次——与 system 登录失败锁定阈值 LOGIN_FAIL_LOCK_THRESHOLD=5 同锚
     * （同款「连续试错达 5 次锁定」语义；正常患者偶发输错 1~2 次即纠正，连续 5 次未命中属枚举探测形态） */
    private static final int FAIL_THRESHOLD = 5;

    /** 失败计数窗口：10 分钟——须短于冷却时长（窗口不变式，见类 javadoc）；窗口内「连续」失败
     * 才累计，正常患者跨日的偶发输错不受影响 */
    private static final Duration COUNT_WINDOW = Duration.ofMinutes(10);

    /** 冷却时长：30 分钟——与 system 登录锁定时长 LOGIN_LOCK_DURATION=30m 同锚（全仓试错锁定同款口径） */
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    /** 摘要算法名（JDK 标准名） */
    private static final String DIGEST_ALGORITHM_SHA256 = "SHA-256";

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param redisTemplate Redis 字符串模板（A.5-1），非空；失败计数与冷却标记的承载通道
     */
    public PortalCredentialRateGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 冷却期前置判定（匿名预约受理第一步）：冷却标记在挂即抛 429 拒绝，阻断冷却期内的枚举
     * 试错触达介质解析；Redis 异常降级放行（频控缺失不阻断主链路，StormGuard 同款）。
     *
     * @param credentialType 介质类型（ID_CARD/VISIT_CARD，controller 词表校验后透传），非空
     * @param credentialNo  介质号（证件号/就诊卡号），非空；仅本调用生命周期内存活，禁入日志
     * @throws BizException OP-1023（429 证件号解析失败频控冷却中）时触发；建议处理策略：提示
     *                      稍后重试或转人工通道，冷却到期自动恢复
     */
    public void checkNotCoolingDown(String credentialType, String credentialNo) {
        boolean cooling;
        try {
            // 缓存读操作：冷却标记存在性查询（键成分为摘要，不含证件号明文）
            cooling = Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(credentialType, credentialNo)));
        } catch (RuntimeException e) {
            // Redis 降级：频控为辅助语义，查询失败放行（warn 留痕，不阻断匿名预约可用性）
            log.warn("证件号频控冷却查询 Redis 异常（降级放行）：介质类型={}，原因={}", credentialType, e.getMessage());
            return;
        }
        if (cooling) {
            // 日志禁打印介质号明文（患者敏感字段脱敏红线），以介质类型+摘要前 8 位留痕锚定
            log.warn(
                    "证件号解析失败达阈值，限流冷却中拒绝受理：介质类型={}，介质号摘要前8位={}",
                    credentialType,
                    digestPrefix(credentialType, credentialNo));
            throw new BizException(
                    OutpatientErrorCode.PORTAL_CREDENTIAL_RATE_LIMITED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "证件号解析失败次数过多，已进入限流冷却，请稍后重试");
        }
    }

    /**
     * 解析失败计数（介质与档案不符/PAT-1001 未命中后调用）：失败计数 INCR+窗口续期，连续失败
     * 达阈值置冷却标记（TTL=冷却时长，到期自然解除）；Redis 异常降级跳过（warn 留痕）。
     *
     * @param credentialType 介质类型（ID_CARD/VISIT_CARD），非空
     * @param credentialNo  介质号（证件号/就诊卡号），非空；禁入日志
     */
    public void recordResolutionFailure(String credentialType, String credentialNo) {
        String failKey = failCountKey(credentialType, credentialNo);
        try {
            // 缓存写操作：Redis INCR 原子计数（窗口内首次失败返回 1；盒装判空防降级场景 NPE）
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count == null) {
                return;
            }
            // 缓存写操作：每次失败重续窗口 TTL（StormGuard 同款自愈续期，禁无过期键）
            redisTemplate.expire(failKey, COUNT_WINDOW);
            if (count >= FAIL_THRESHOLD) {
                // 达阈值置冷却标记（TTL=冷却时长；冷却>窗口的不变式保证冷却结束即全新计数窗口）
                redisTemplate.opsForValue().set(cooldownKey(credentialType, credentialNo), "1", COOLDOWN);
                log.warn(
                        "证件号连续解析失败达阈值，进入限流冷却：介质类型={}，连续失败={}，阈值={}，冷却分钟数={}",
                        credentialType,
                        count,
                        FAIL_THRESHOLD,
                        COOLDOWN.toMinutes());
            }
        } catch (RuntimeException e) {
            // Redis 降级：计数失败仅留痕（本窗口少计一次，下次失败自愈续期）
            log.warn("证件号频控计数 Redis 操作失败（降级跳过）：介质类型={}，原因={}", credentialType, e.getMessage());
        }
    }

    /**
     * 解析成功清零计数（「连续失败」语义的成功打断面）：删除失败计数键；冷却标记不动（已进入
     * 冷却即完整执行冷却期，杜绝临界反复）；Redis 异常降级跳过（窗口 TTL 兜底自然过期）。
     *
     * @param credentialType 介质类型（ID_CARD/VISIT_CARD），非空
     * @param credentialNo  介质号（证件号/就诊卡号），非空；禁入日志
     */
    public void clearFailureCount(String credentialType, String credentialNo) {
        try {
            // 缓存写操作：删除失败计数键（成功打断连续计数）
            redisTemplate.delete(failCountKey(credentialType, credentialNo));
        } catch (RuntimeException e) {
            // Redis 降级：清零失败仅留痕（计数键窗口 TTL 到期自然归零）
            log.warn("证件号频控计数清零 Redis 异常（窗口 TTL 兜底）：介质类型={}，原因={}", credentialType, e.getMessage());
        }
    }

    /**
     * 失败计数键拼装：fy:outpatient:portal-cred-fail:{SHA-256(类型:介质号)}。
     *
     * @param credentialType 介质类型，非空
     * @param credentialNo  介质号，非空
     * @return 失败计数键文本，非空；不含介质号明文
     */
    private static String failCountKey(String credentialType, String credentialNo) {
        return FAIL_COUNT_KEY_PREFIX + digest(credentialType, credentialNo);
    }

    /**
     * 冷却标记键拼装：fy:outpatient:portal-cred-cool:{SHA-256(类型:介质号)}。
     *
     * @param credentialType 介质类型，非空
     * @param credentialNo  介质号，非空
     * @return 冷却标记键文本，非空；不含介质号明文
     */
    private static String cooldownKey(String credentialType, String credentialNo) {
        return COOLDOWN_KEY_PREFIX + digest(credentialType, credentialNo);
    }

    /**
     * 介质号摘要前 8 位（日志留痕锚点：不可逆短前缀，兼顾排障定位与脱敏）。
     *
     * @param credentialType 介质类型，非空
     * @param credentialNo  介质号，非空
     * @return 摘要前 8 位文本，非空
     */
    private static String digestPrefix(String credentialType, String credentialNo) {
        return digest(credentialType, credentialNo).substring(0, 8);
    }

    /**
     * 计算 {@code 介质类型:介质号} 的 SHA-256 小写 hex 摘要（64 位；Redis 键成分——证件号明文
     * 禁入键，DeadLetterListener sha256Hex 同款形态）。
     *
     * @param credentialType 介质类型，非空
     * @param credentialNo  介质号，非空
     * @return 64 位小写十六进制摘要，非空
     * @throws IllegalStateException SHA-256 算法不可用（JDK 环境异常，理论不可达；EX-19 C 类
     *                               留痕：环境级防御断言，走全局 500 兜底，不转业务错误码）
     */
    private static String digest(String credentialType, String credentialNo) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256);
            byte[] hashed =
                    messageDigest.digest((credentialType + ":" + credentialNo).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("证件号频控摘要算法不可用（JDK 环境异常）", e);
        }
    }
}

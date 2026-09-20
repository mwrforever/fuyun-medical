package com.fuyun.outpatient.cache;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 号源池 Redis 预扣闸（M03 Spec 3.2 双道闸第一道，cache/ 领域缓存类宪法 A.5-16/A.5-17）：
 * Lua 原子脚本承载「余量校验+扣减/回补+EXPIRE」单步原子语义，杜绝应用层读-改-写竞态。
 * 键 {@code fy:outpatient:pool:{poolId}}（A.5-1 冒号分层；{poolId} 为 Redis Cluster hash tag），
 * String 序列化（StringRedisTemplate 承载，禁 JDK 序列化），每次操作必携 TTL（禁无过期键）。
 *
 * <p>职责边界：本类只做脚本执行与结果透传，不捕获 Redis 异常——异常上抛由调用方降级直连 DB
 * 条件更新（功能不中断，warn 留痕，Task 5 预约侧接线）。脚本加载：构造期从 classpath
 * resources/lua/ 装入 DefaultRedisScript（Redisson 引入前的 StringRedisTemplate 形态，brief 定稿）。
 * 线程安全：无状态单例（DefaultRedisScript 只读共享）。装配归 OutpatientWebConfig @Import。
 */
public class PoolRedisGate {

    /** 池键前缀（suffix=poolId，A.5-1 fy:{module}:{biz}:{id} 分层） */
    private static final String KEY_PREFIX = "fy:outpatient:pool:{";

    /** 池键后缀（Redis Cluster hash tag 收口） */
    private static final String KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;

    /** 原子预扣脚本（lua/pool_deduct.lua，返回扣减后余量/-1 余量不足/-2 键缺失） */
    private final RedisScript<Long> deductScript;

    /** 原子回补脚本（lua/pool_release.lua，返回封顶后余量/-1 键缺失） */
    private final RedisScript<Long> releaseScript;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param redisTemplate Redis 字符串模板，非空；来源：Boot 自动装配（Key/Value 均 String 序列化）
     */
    public PoolRedisGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.deductScript = loadScript("lua/pool_deduct.lua");
        this.releaseScript = loadScript("lua/pool_release.lua");
    }

    /**
     * 原子预扣（余量校验+DECRBY+EXPIRE 单步原子）：返回扣减后余量。
     *
     * @param poolId 池行主键；来源：预约请求定位的号源行
     * @param total  池总量（脚本签名对称预留位）；来源：池行 total_quota 读回
     * @param ttl    池键 TTL（sched_date 次日 02:00 对账窗口缓冲）；来源：调用方按排班日计算
     * @return 扣减后余量；-1 余量不足（调用方判 OP-1003）；-2 键缺失（未预热/已过期降级信号）
     * @throws org.springframework.data.redis.RedisSystemException Redis 不可用时原样上抛（调用方降级直连 DB）
     */
    public int deduct(long poolId, long total, Duration ttl) {
        Long remain = redisTemplate.execute(
                deductScript,
                List.of(poolKey(poolId)),
                // 扣减量恒为 1（单次预约占一号）；total 为脚本签名对称预留位；TTL 秒交脚本 EXPIRE
                "1",
                String.valueOf(total),
                String.valueOf(ttl.toSeconds()));
        return remain.intValue();
    }

    /**
     * 原子回补（INCRBY+越界封顶+EXPIRE 单步原子）：退号/取消/超时释放回池唯一入口。
     *
     * @param poolId 池行主键；来源：退号/释放载荷定位的号源行
     * @param total  池总量（越界封顶锚，对账防漂移）；来源：池行 total_quota 读回
     * @param ttl    池键 TTL（回补后续期，维持对账窗口覆盖）；来源：调用方按排班日计算
     * @return 封顶后余量；-1 键缺失（对账窗口外残留，调用方按需重预热）
     * @throws org.springframework.data.redis.RedisSystemException Redis 不可用时原样上抛（调用方降级直连 DB）
     */
    public int release(long poolId, long total, Duration ttl) {
        Long remain = redisTemplate.execute(
                releaseScript,
                List.of(poolKey(poolId)),
                // 回补量恒为 1（单次退号回一号）；total 为越界封顶锚；TTL 秒交脚本 EXPIRE
                "1",
                String.valueOf(total),
                String.valueOf(ttl.toSeconds()));
        return remain.intValue();
    }

    /**
     * 池键预热（SET total+TTL）：放号生成时消除键缺失态（deduct -2 分支的逆操作），预约高峰
     * 第一道闸自本调用起生效。
     *
     * @param poolId 池行主键；来源：放号生成的新建池行
     * @param total  池总量（初始余量=total_quota）；来源：生成池行实体
     * @param ttl    池键 TTL（sched_date 次日 02:00 对账窗口缓冲）；来源：放号按排班日计算
     */
    public void prime(long poolId, long total, Duration ttl) {
        redisTemplate.opsForValue().set(poolKey(poolId), String.valueOf(total), ttl);
    }

    /**
     * 装载 Lua 原子脚本（构造期一次性，classpath resources/lua/；结果类型 Long 对齐 Redis 整数回包）。
     *
     * @param classpathLocation 脚本类路径位置（如 lua/pool_deduct.lua）
     * @return 只读脚本对象，非空；脚本文件缺失经 ResourceScriptSource 装载期抛出（fail-fast）
     */
    private static RedisScript<Long> loadScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 拼装池键：fy:outpatient:pool:{poolId}——{poolId} 为 Redis Cluster hash tag（A.5-17），
     * 同一池行的全部原子操作命中同一分片。
     *
     * @param poolId 池行主键
     * @return 池键文本，非空
     */
    private static String poolKey(long poolId) {
        return KEY_PREFIX + poolId + KEY_SUFFIX;
    }
}

package com.fuyun.outpatient.cache;

import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 候诊队列 Redis ZSET 存储（M03 候诊叫号加速视图，cache/ 领域缓存类宪法 A.5-16/A.5-17）：
 * 键 {@code fy:outpatient:queue:{deptCode}}（A.5-1 冒号分层；{deptCode} 兼作 Redis Cluster
 * hash tag），member=String.valueOf(ticketPk)，score 编码=priority_score*1e8+queue_seq（封顶
 * 999 后量级 < 2^53 安全），TTL=当日末+2h（与池键次日 02:00 对账锚同值）。
 *
 * <p><b>权威面声明</b>：queue_ticket 的 WAITING 行为权威——{@link #rebuildIfMissing} 在键缺失
 * （Redis 重启/淘汰后首访）时按权威行整体重建（叫号服务重启后队列从排队表完整恢复，Spec :210）；
 * 键在位零写（幂等——已出队票的 CAS 与 ZSET 移除同步，回灌即重复叫号）。医生匹配语义：票
 * doctor_id 为空=未指派任一医生可叫，指派一致才可叫——匹配需读票据行（member 仅含 pk，入队时
 * 指派未定），故构造器注入 {@link QueueTicketMapper} 做匹配读（队列规模=单诊区当日量，有界）。
 *
 * <p>职责边界：本类只做脚本执行与读写透传，不捕获 Redis 异常——异常上抛由调用方按 Redis 不可用
 * 口径处置。脚本加载：构造期从 classpath resources/lua/ 装入（PoolRedisGate 同款 StringRedisTemplate
 * 形态）。线程安全：无状态单例。装配归 OutpatientWebConfig @Import。
 */
public class QueueZsetStore {

    /** 队列键前缀（suffix=deptCode，A.5-1 fy:{module}:{biz}:{id} 分层） */
    private static final String KEY_PREFIX = "fy:outpatient:queue:{";

    /** 队列键后缀（Redis Cluster hash tag 收口） */
    private static final String KEY_SUFFIX = "}";

    /** 队列键 TTL 锚点时刻：当日末+2h=次日 02:00（与池键对账锚同值，A.5-1 禁无 TTL 键） */
    private static final LocalTime QUEUE_KEY_TTL_ANCHOR = LocalTime.of(2, 0);

    /** 可出队叫号的票态词表（WAITING 候诊/PASSED 过号再入；fix round 1 Important-2 同步口径） */
    private static final Set<TicketStatus> QUEUEABLE_STATUSES = Set.of(TicketStatus.WAITING, TicketStatus.PASSED);

    private final StringRedisTemplate redisTemplate;

    private final QueueTicketMapper queueTicketMapper;

    /** 原子出队守卫脚本（lua/queue_poll_top.lua，返回 1=出队成功/0=已被并发取走） */
    private final RedisScript<Long> pollScript;

    /**
     * 全参构造器（装配归 OutpatientWebConfig @Import）。
     *
     * @param redisTemplate     Redis 字符串模板，非空；来源：Boot 自动装配（Key/Value 均 String 序列化）
     * @param queueTicketMapper 票据 mapper，非空；pollTop 医生匹配读（member 仅含 pk，指派在票行）
     */
    public QueueZsetStore(StringRedisTemplate redisTemplate, QueueTicketMapper queueTicketMapper) {
        this.redisTemplate = redisTemplate;
        this.queueTicketMapper = queueTicketMapper;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/queue_poll_top.lua")));
        script.setResultType(Long.class);
        this.pollScript = script;
    }

    /**
     * 入队（建票/过号重排/调级重排共用）：ZADD member=pk、score=优先级编码值，并续期 TTL
     * （当日末+2h，禁无 TTL 键）。
     *
     * <p>score 形参取 {@code long}：冻结编码 priority_score*1e8+queue_seq 量级 ~1e11 超出 int
     * 上限（brief 冻结签名 int 为笔误，ZSET score 为 double 承载、long 在 2^53 内无损转换）。
     *
     * @param deptCode 队列标识（=dept_code），非空
     * @param ticketPk 票据主键，非空
     * @param score    优先级编码分（priority_score*1e8+queue_seq），由调用方按冻结公式计算
     */
    public void enqueue(String deptCode, long ticketPk, long score) {
        String key = keyOf(deptCode);
        // 缓存写操作：ZSET 入队（member=pk；同 member 重复 ZADD 即 score 更新——调级重排复用）
        redisTemplate.opsForZSet().add(key, String.valueOf(ticketPk), score);
        redisTemplate.expire(key, ttlOfTodayEndPlus2h());
    }

    /**
     * 原子出队（按序首个可叫票）：ZRANGE 全量按 score 升序扫描，取首个「票态可叫（WAITING 候诊/
     * PASSED 过号再入）且未指派或指派一致」的票，经 Lua 守卫原子 ZREM（并发双叫仅一方成功，败者
     * 继续扫描下一位）；票行缺失或票态不可叫（已叫/已接诊/已取消等遗留态成员——如重呼后未再过号的
     * 票）则原子移除清理后继续扫描；无匹配返回 null。
     *
     * @param deptCode 队列标识，非空
     * @param doctorId 叫号医生 id，非空
     * @return 出队票据主键；队列空或无可叫票（指派均不匹配）返回 null（调用方 200 空语义）
     */
    public Long pollTop(String deptCode, String doctorId) {
        String key = keyOf(deptCode);
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
        if (members == null || members.isEmpty()) {
            return null;
        }
        for (String member : members) {
            long ticketPk = Long.parseLong(member);
            QueueTicket ticket = queueTicketMapper.selectById(ticketPk);
            // 失效成员清理：票行缺失或票态不在可叫词表（遗留成员留驻会令后续叫号误触状态冲突）——
            // Lua 原子移除后继续扫描
            if (ticket == null || !QUEUEABLE_STATUSES.contains(ticket.getStatus())) {
                redisTemplate.execute(pollScript, List.of(key), member);
                continue;
            }
            // 匹配谓词：未指派或指派一致才可叫（指派不一致保留成员给指派医生）
            if (ticket.getDoctorId() != null && !ticket.getDoctorId().equals(doctorId)) {
                continue;
            }
            // 缓存写操作：Lua 守卫原子出队（ZSCORE 在位才 ZREM，防并发双叫同票）
            Long polled = redisTemplate.execute(pollScript, List.of(key), member);
            if (polled != null && polled == 1L) {
                return ticketPk;
            }
        }
        return null;
    }

    /**
     * 移除成员（跨队列转接放旧票路径）：ZREM 幂等，成员不存在返回 0 不报错。
     *
     * @param deptCode 队列标识，非空
     * @param ticketPk 票据主键，非空
     */
    public void remove(String deptCode, long ticketPk) {
        redisTemplate.opsForZSet().remove(keyOf(deptCode), String.valueOf(ticketPk));
    }

    /**
     * 队列快照（按 score 升序前 N 个票据主键）：REST 快照双通道的 Redis 侧序（与 queue_time
     * 权威序一致——queue_seq 与建行时序同源）。
     *
     * @param deptCode 队列标识，非空
     * @param limit    取前 N 个，正数
     * @return 票据主键列表（score 升序）；空队列返回空列表
     */
    public List<Long> snapshot(String deptCode, int limit) {
        Set<String> members = redisTemplate.opsForZSet().range(keyOf(deptCode), 0, limit - 1L);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).toList();
    }

    /**
     * 队列惰性重建（叫号服务重启后队列从排队表完整恢复，Spec :210）：ZSET 键缺失（Redis 重启/
     * 淘汰后首访）时按 queue_ticket 待重叫权威行（WAITING 候诊+PASSED 过号再入——调用方经
     * selectWaiting 取行，fix round 1 Important-2 裁决①）整体重建；键在位零写（幂等——已出队票的
     * CAS 与 ZSET 移除同步，回灌即重复叫号）。重建分取票据 priority_score 列冻结编码（过号降级分
     * 不持久化，重启后 PASSED 票按列值回到原优先级相对位次）。
     *
     * @param deptCode     诊区队列标识，非空
     * @param ticketScores 待重叫票 pk→score（priority_score*1e8+queue_seq，long 编码）映射，
     *                     调用方由库行计算
     * @return 重建票数；键在位返回 -1（跳过信号）
     */
    public int rebuildIfMissing(String deptCode, Map<Long, Long> ticketScores) {
        String key = keyOf(deptCode);
        // 键在位=正常态：零写返回，防把已 CALLED/SERVING 等非在队票回灌队列（在队口径=调用方
        // selectWaiting 词表：WAITING+PASSED）
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return -1;
        }
        // 键缺失=重启/淘汰后首访：待重叫权威行整体 ZADD 重建（score 公式与入队同源）
        ZSetOperations<String, String> zsetOps = redisTemplate.opsForZSet();
        ticketScores.forEach((ticketPk, score) -> zsetOps.add(key, String.valueOf(ticketPk), score));
        redisTemplate.expire(key, ttlOfTodayEndPlus2h()); // 重建键与常规入队同 TTL 规范（当日末+2h）
        return ticketScores.size();
    }

    /**
     * 拼装队列键：fy:outpatient:queue:{deptCode}——{deptCode} 为 Redis Cluster hash tag（A.5-17），
     * 同一队列的全部原子操作命中同一分片。
     *
     * @param deptCode 队列标识，非空
     * @return 队列键文本，非空
     */
    private static String keyOf(String deptCode) {
        return KEY_PREFIX + deptCode + KEY_SUFFIX;
    }

    /**
     * 队列键 TTL 计算：当日末+2h（次日 02:00，与池键对账锚同值；队列为当日语义，隔日自然过期）。
     *
     * @return 距锚点时刻的时长（恒为正）
     */
    private static Duration ttlOfTodayEndPlus2h() {
        return Duration.between(
                LocalDateTime.now(), LocalDateTime.now().plusDays(1).with(QUEUE_KEY_TTL_ANCHOR));
    }
}

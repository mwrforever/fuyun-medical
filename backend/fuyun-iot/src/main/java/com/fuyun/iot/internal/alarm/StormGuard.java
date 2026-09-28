package com.fuyun.iot.internal.alarm;

import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.properties.AlarmProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 风暴抑制器（FU-M14-08 五项抑制的判定执行点）：①同源聚合（CAS 命中活跃行仅计数）、③离线抑制
 * （设备已有 ACTIVE 离线告警时跳过其衍生遥测告警）、④风暴态（单位时间触发量超基线置
 * {@code fy:iot:alarm:storm:{ruleId}} TTL=风暴窗口，风暴期非危急只入库不推 WS，告警号入补推队列、
 * 解除后补推）。抑制②抖动防护（规则参数校验拒保存）归 AlarmRuleServiceImpl、抑制⑤升级动作
 * （含事件发布）归 AlarmEngine——判定面集中本类、写面就近归属。
 *
 * <p>Redis 降级语义：风暴抑制为辅助语义，Redis 异常不阻断告警触发主链路（降级为不置位不抑制、
 * 补推省略并 warn 留痕，与映射缺失防刷屏键同款口径）；盒装返回值（increment/size/hasKey 可能
 * 为 null）一律 null 安全判空，不因 mock/降级场景 NPE。
 *
 * <p>无状态单例（Redis 承载全部窗口状态，多实例共享判 violence 一致）；装配归 IotConfig @Import
 * （com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 */
@Slf4j
public class StormGuard {

    /** 风暴标记键前缀：fy:iot:alarm:storm:（brief 冻结，拼 ruleId 为完整键） */
    private static final String STORM_KEY_PREFIX = "fy:iot:alarm:storm:";

    /** 触发计数键前缀：fy:iot:alarm:rate:（拼 ruleId，风暴窗口内 INCR 计数） */
    private static final String RATE_KEY_PREFIX = "fy:iot:alarm:rate:";

    /** 补推队列键前缀：fy:iot:alarm:pending:（拼 ruleId，风暴期被抑制告警号清单） */
    private static final String PENDING_KEY_PREFIX = "fy:iot:alarm:pending:";

    /** 补推队列 TTL：1 小时（风暴长挂时遗留队列自然过期，防无限积压） */
    private static final Duration PENDING_TTL = Duration.ofHours(1);

    /** 引擎触发的审计操作人标识（引擎无登录上下文，与审计列默认值同源） */
    public static final String SYSTEM_OPERATOR = "system";

    private final IotAlarmMapper alarmMapper;

    private final IotAlarmRuleMapper ruleMapper;

    private final StringRedisTemplate redisTemplate;

    private final AlarmProperties properties;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param alarmMapper    告警行 mapper，非空；抑制① CAS 与抑制③活跃行查询通道
     * @param ruleMapper     告警规则 mapper，非空；抑制③规则类型批量装载通道
     * @param redisTemplate  String 模板（A.5-1），非空；抑制④计数/风暴标记/补推队列通道
     * @param properties     告警引擎配置属性，非空；风暴窗口与基线
     */
    public StormGuard(
            IotAlarmMapper alarmMapper,
            IotAlarmRuleMapper ruleMapper,
            StringRedisTemplate redisTemplate,
            AlarmProperties properties) {
        this.alarmMapper = alarmMapper;
        this.ruleMapper = ruleMapper;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 触发抑制判定（抑制①③联合决策点）：遥测衍生路径先查设备活跃离线告警（抑制③），随后
     * 以 CAS 尝试同源聚合计数（抑制①）——命中活跃行仅计数不新发，未命中放行新发。
     *
     * @param ruleId            命中规则 ID，非空
     * @param deviceId          触发源设备号，非空
     * @param operator          操作者（引擎路径固定 system），非空
     * @param telemetryDerived  true=遥测衍生告警（阈值/透传，受抑制③约束）；false=离线规则源
     *                          自身（不受抑制③约束，否则离线告警永无法创建）
     * @return 判定结果：Aggregated 聚合计数 / OfflineSuppressed 离线抑制 / Proceed 放行新发，非空
     */
    public TriggerOutcome decide(long ruleId, String deviceId, String operator, boolean telemetryDerived) {
        if (telemetryDerived && hasActiveOfflineAlarm(deviceId)) {
            // 抑制③：设备已知离线（ACTIVE 离线告警在挂），其遥测衍生告警跳过——离线期遥测为陈旧数据
            log.info("抑制③离线抑制：设备已有 ACTIVE 离线告警，跳过衍生遥测告警：ruleId={}，deviceId={}", ruleId, deviceId);
            return new TriggerOutcome.OfflineSuppressed();
        }
        // 数据库写操作：抑制① CAS——命中活跃行仅聚合计数（不新发行不发布事件），返回 1 即聚合
        if (alarmMapper.incrementTriggerIfActive(ruleId, deviceId, operator) > 0) {
            log.info("抑制①同源聚合：命中活跃告警行仅触发计数+1：ruleId={}，deviceId={}", ruleId, deviceId);
            return new TriggerOutcome.Aggregated();
        }
        return new TriggerOutcome.Proceed();
    }

    /**
     * 活跃告警行存在判定（恢复带内不重复触发的预检面）：同规则同设备存在 ACTIVE 行时，引擎在
     * 触发前先以恢复带判定是否值得聚合计数。
     *
     * @param ruleId   规则 ID，非空
     * @param deviceId 设备号，非空
     * @return true=存在同源活跃告警行
     */
    public boolean hasActiveAlarm(long ruleId, String deviceId) {
        // 数据库读操作：抑制① CAS 的查询面（同条件族，真实聚合以 CAS 影响行数为准）
        return alarmMapper
                .selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotAlarmEntity>lambdaQuery()
                        .eq(IotAlarmEntity::getRuleId, ruleId)
                        .eq(IotAlarmEntity::getDeviceId, deviceId)
                        .eq(IotAlarmEntity::getStatus, com.fuyun.iot.enums.AlarmStatus.ACTIVE))
                .stream()
                .findAny()
                .isPresent();
    }

    /**
     * 抑制④触发计数与风暴态判定：同规则窗口计数+1（每次自增后续期计数键 TTL=风暴窗口——首触发
     * 后进程崩溃或当次 expire 异常被降级吞并时不会遗留永久键，下一次成功触发自愈重续），超基线置
     * 风暴标记 {@code fy:iot:alarm:storm:{ruleId}}（TTL=风暴窗口）。
     *
     * @param ruleId 命中规则 ID，非空
     * @return true=当前处于风暴态（计数超基线或既有风暴标记未过期）
     */
    public boolean recordTriggerAndCheckStorm(long ruleId) {
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            // Redis INCR 原子计数：窗口内首触发返回 1
            Long count = ops.increment(RATE_KEY_PREFIX + ruleId);
            if (count == null) {
                // 降级计数（理论不可达）：沿用既有风暴态
                return isStorm(ruleId);
            }
            renewRateKeyTtlQuietly(ruleId);
            if (count > properties.stormBaseline()) {
                // 超基线：置风暴标记（TTL=风暴窗口，到期自然解除）
                ops.set(STORM_KEY_PREFIX + ruleId, "1", properties.stormWindow());
                log.warn("风暴态置位：规则触发量超基线：ruleId={}，窗口计数={}，基线={}", ruleId, count, properties.stormBaseline());
                return true;
            }
        } catch (RuntimeException e) {
            // Redis 降级：风暴抑制为辅助语义，计数失败不阻断触发链路（降级沿用既有风暴态）
            log.warn("风暴计数 Redis 操作失败（降级沿用既有风暴态）：ruleId={}，原因={}", ruleId, e.getMessage());
        }
        return isStorm(ruleId);
    }

    /**
     * 计数键 TTL 续期（独立降级面）：每次自增后重续风暴窗口——仅首触发续期的原实现存在「首触发后
     * 进程崩溃/当次 expire 异常吞并 → 计数键永久无 TTL → 计数只增不清 → 非危急推送被无限期抑制」
     * 的不自愈风险；改为每次续期后，任何一次成功触发即重武 TTL，遗留键最迟下次触发自愈。
     *
     * @param ruleId 规则 ID，非空
     */
    private void renewRateKeyTtlQuietly(long ruleId) {
        try {
            // Redis EXPIRE 单命令：固定窗口计数键与窗口起点无关，重续仅保证键生命周期有界
            redisTemplate.expire(RATE_KEY_PREFIX + ruleId, properties.stormWindow());
        } catch (RuntimeException e) {
            // 续期失败与计数/风暴判定解耦：本轮降级留痕，下次成功触发自愈（不产生永久键语义）
            log.warn("风暴计数键 TTL 续期失败（下次触发自愈）：ruleId={}，原因={}", ruleId, e.getMessage());
        }
    }

    /**
     * 风暴态查询：规则的风暴标记是否在挂。
     *
     * @param ruleId 规则 ID，非空
     * @return true=风暴态（Redis 异常降级为 false——风暴抑制缺失不阻断推送主链路）
     */
    public boolean isStorm(long ruleId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(STORM_KEY_PREFIX + ruleId));
        } catch (RuntimeException e) {
            log.warn("风暴态查询 Redis 异常（降级为非风暴）：ruleId={}，原因={}", ruleId, e.getMessage());
            return false;
        }
    }

    /**
     * 风暴期被抑制告警号入补推队列（抑制④解除后补推的登记面）。
     *
     * @param ruleId  规则 ID，非空
     * @param alarmNo 被抑制的告警业务号，非空
     */
    public void queueDeferredPush(long ruleId, String alarmNo) {
        try {
            // 消息发送（Redis 队列登记）：补推队列尾插 + 续期，风暴长挂时遗留队列自然过期防积压
            redisTemplate.opsForList().rightPush(PENDING_KEY_PREFIX + ruleId, alarmNo);
            redisTemplate.expire(PENDING_KEY_PREFIX + ruleId, PENDING_TTL);
            log.info("风暴期告警入补推队列：ruleId={}，alarmNo={}", ruleId, alarmNo);
        } catch (RuntimeException e) {
            // Redis 降级：补推登记失败仅告警留痕（告警行已落库，WS 推送缺失可由订阅方主动查询兜底）
            log.warn("补推队列登记失败（告警行已落库，本次推送缺失）：ruleId={}，alarmNo={}，原因={}", ruleId, alarmNo, e.getMessage());
        }
    }

    /**
     * 风暴解除后取回补推队列（抑制④「解除后补推」执行面）：风暴标记不在挂时排空队列并删键。
     *
     * @param ruleId 规则 ID，非空
     * @return 待补推告警号清单；风暴未解除或队列为空返回空清单，非空
     */
    public List<String> drainDeferredIfStormCleared(long ruleId) {
        try {
            if (isStorm(ruleId)) {
                // 风暴未解除：抑制持续生效，不取队列
                return Collections.emptyList();
            }
            String pendingKey = PENDING_KEY_PREFIX + ruleId;
            Long size = redisTemplate.opsForList().size(pendingKey);
            if (size == null || size == 0) {
                return Collections.emptyList();
            }
            // 消息消费（Redis 队列排空）：一次性左弹出队全部遗留告警号
            List<String> drained = redisTemplate.opsForList().leftPop(pendingKey, size);
            redisTemplate.delete(pendingKey);
            if (drained != null && !drained.isEmpty()) {
                log.info("风暴解除补推排空：ruleId={}，count={}", ruleId, drained.size());
            }
            return drained == null ? Collections.emptyList() : drained;
        } catch (RuntimeException e) {
            // Redis 降级：补推排空失败留痕，遗留队列待下次触发再排（登记 TTL 1h 兜底过期）
            log.warn("补推队列排空 Redis 异常（遗留队列待下次排空）：ruleId={}，原因={}", ruleId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 抑制③判定：设备是否已有 ACTIVE 离线告警（活跃行 → 规则批量装载 → 任一 OFFLINE 型即命中）。
     *
     * @param deviceId 设备号，非空
     * @return true=设备存在离线型规则的活跃告警行
     */
    private boolean hasActiveOfflineAlarm(String deviceId) {
        // 数据库读操作：设备活跃告警行单查（扫描面 @Select，条件内嵌 deleted=0）
        List<IotAlarmEntity> activeRows = alarmMapper.selectActiveByDevice(deviceId);
        if (activeRows.isEmpty()) {
            return false;
        }
        // 数据库读操作：活跃行规则 id 集合批量装载（宪法 A.4.3-14 拒循环内单查），内存判 OFFLINE 型
        List<Long> ruleIds =
                activeRows.stream().map(IotAlarmEntity::getRuleId).distinct().toList();
        return ruleMapper.selectBatchIds(ruleIds).stream()
                .anyMatch(rule -> rule.getRuleType() == AlarmRuleType.OFFLINE);
    }

    /**
     * 触发抑制判定结果（sealed 三分支，宪法 A.1-3 编译器穷尽检查——引擎侧 instanceof 链
     * 对新分支编译期强制补齐）。
     */
    public sealed interface TriggerOutcome {

        /** 抑制①命中：同源活跃行已聚合计数（trigger_count+1），调用方不新发不发布 */
        record Aggregated() implements TriggerOutcome {}

        /** 抑制③命中：设备已有 ACTIVE 离线告警，衍生遥测告警跳过 */
        record OfflineSuppressed() implements TriggerOutcome {}

        /** 放行：无抑制命中，调用方可新发告警行 */
        record Proceed() implements TriggerOutcome {}
    }
}

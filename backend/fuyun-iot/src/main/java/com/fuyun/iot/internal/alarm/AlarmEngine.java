package com.fuyun.iot.internal.alarm;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.iot.api.payload.AlarmEscalatedPayload;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.AlarmStatus;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.DeviceAlarmFrame;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.properties.AlarmProperties;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.BindingVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 告警引擎（FU-M14-08，P2 PR-2 Task 7）：三类规则源的进程内评估组件——旁路评估不阻塞遥测落库
 * 主链路（ingest afterCommit 后调起，评估自身独立短事务；禁在 ingest 事务内直呼）。
 *
 * <p><b>三类规则源评估语义</b>：①阈值规则（THRESHOLD）=当前值越阈值且持续 duration_secs（越限
 * 回合标记 {@code fy:iot:alarm:breach:{ruleId}:{deviceId}:{metricCode}} 承载持续时长累计，批次频度
 * 逐次核算）；恢复带内（含已恢复侧）不重复触发——回合复位仅在值回到恢复带边界内时发生，活跃期内
 * 越恢复带的重复触发仅聚合计数；②透传规则（DEVICE_ALARM）=IoTDA device.alarm 帧命中（经
 * {@code evaluateDeviceAlarm}，消费侧第四形态解析产物直入）；③离线规则（OFFLINE）=
 * {@link OfflineDetector} 惰性扫描随 evaluate 调起（ONLINE 设备最后在线超时即断流）。
 *
 * <p><b>五项抑制</b>（判定集中在 {@link StormGuard}）：①同源聚合 CAS 命中活跃行仅计数、③离线
 * 抑制衍生遥测告警、④风暴态非危急只入库不推 WS（补推队列解除后排空）在触发链内生效；②抖动
 * 防护（THRESHOLD 规则参数齐备校验）归 AlarmRuleServiceImpl 拒保存面；⑤升级动作为读时惰性判定
 * （inpatient 会诊逾期先例）：随 evaluate 扫描 ACTIVE+CRITICAL 候选，越 escalate_after_secs 未确认
 * → CAS 升级（DB escalation_count 旧值限定防重发，仅首个升级方发布 iot.alarm.escalated）。
 *
 * <p><b>事件与推送时机</b>：新告警落行与 iot.alarm.triggered 发布同事务（事务内 publishEvent →
 * IotDomainPublisher AFTER_COMMIT 出 MQ，宪法 A.4.2-7）；WS 推送挂本事务 afterCommit（无事务
 * 同步上下文时直推，单测直调场景行为不变）。评估由 ingest afterCommit 调起——该时点原 ingest
 * 事务已提交但同步上下文仍激活，默认 REQUIRED 会令引擎加入已提交的死事务（afterCommit 快照
 * 已触发完毕，链内 publishEvent 的 AFTER_COMMIT 监听与推送侧新注册同步均不再执行，Spring
 * TransactionSynchronization#afterCommit javadoc 明文场景），故 evaluate 强制
 * {@link Propagation#REQUIRES_NEW}：挂起死事务、新建短事务承载落行与事件发布，新事务提交时
 * AFTER_COMMIT 监听与 WS 推送同步正常触发；evaluateDeviceAlarm 唯一入口为消费线程直入
 * （无激活事务），REQUIRED 语义不受传播选择影响，维持现状。单条落行再收进 perAlarmTx
 * （REQUIRES_NEW）独立短事务：PG 下唯一索引冲突只中止单条事务，同批其余「规则×设备」评估
 * 不连带回滚（25P02 隔离，VitalSign replayLookupTx 同款范式）。
 *
 * <p>Redis 降级语义：越限回合标记/最新值快照为辅助状态，Redis 异常降级为跳过该规则设备评估并
 * warn 留痕，不阻断评估链其余部分（与风暴抑制降级同口径）。
 *
 * <p>归 internal/alarm/ 包：容器驱动链路的模块内组件，禁止外部引用（宪法 B.1）；装配归
 * fuyun-app IotConfig @Import。
 */
@Slf4j
public class AlarmEngine {

    /** 越限回合标记键前缀：fy:iot:alarm:breach:（A.5-1 命名，拼 ruleId:deviceId:metricCode） */
    private static final String BREACH_KEY_PREFIX = "fy:iot:alarm:breach:";

    /** 最新值快照键前缀：fy:iot:snapshot:latest:（brief 冻结形态，实测 P0 无写入方——本类补写） */
    private static final String LATEST_SNAPSHOT_KEY_PREFIX = "fy:iot:snapshot:latest:";

    /** 越限回合标记 TTL 余量：持续时长之外追加 60s（覆盖批次间隔抖动，防标记先行过期丢回合） */
    private static final Duration BREACH_TTL_GRACE = Duration.ofSeconds(60);

    /** 离线告警指标编码固定值（离线无遥测指标语义，载荷契约 metricCode 非空的占位词表项） */
    private static final String OFFLINE_METRIC_CODE = "DEVICE_OFFLINE";

    /** 触发值列宽防线：iot_alarm.trigger_value VARCHAR(255)，超长截断留痕 */
    private static final int TRIGGER_VALUE_MAX_LENGTH = 255;

    /** 病区路由失败跳过口径的日志锚（告警无病区归属时无法定推 WS 主题，仅落行） */
    private static final String WARD_UNRESOLVED_SKIP_LOG = "告警无病区归属（无绑定快照且设备未编病区），跳过新发";

    private final IotAlarmRuleMapper ruleMapper;

    private final IotAlarmMapper alarmMapper;

    private final IotDeviceMapper deviceMapper;

    private final IBindingService bindingService;

    private final IotSeqGate seqGate;

    private final ApplicationEventPublisher events;

    private final ITelemetryPushService pushService;

    private final StormGuard stormGuard;

    private final OfflineDetector offlineDetector;

    private final StringRedisTemplate redisTemplate;

    private final AlarmProperties properties;

    /**
     * 单条告警独立事务载体（REQUIRES_NEW 写模板）：PostgreSQL 下唯一索引冲突即中止当前事务
     * （25P02 aborted-transaction 态），同事务内再发语句必失败——批次评估（evaluate REQUIRES_NEW）
     * 对同批其余「规则×设备」还要继续抑制 CAS 与升级扫描，若单条落行留在批事务内，一处并发冲突
     * 将连带回滚整批告警。故每条告警的「落行 + 事务内事件发布 + afterCommit 推送/补推登记」收进
     * 本模板独立短事务（挂起外层批事务），冲突只丢单条（幂等跳过），其余评估照常（VitalSign
     * replayLookupTx 同款范式）。
     */
    private final TransactionTemplate perAlarmTx;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param ruleMapper       告警规则 mapper，非空；三类规则装载通道
     * @param alarmMapper      告警行 mapper，非空；落行与 CAS 通道
     * @param deviceMapper     设备档案 mapper，非空；病区兜底与离线候选通道
     * @param bindingService   绑定域服务，非空；绑定快照五元组富化出口
     * @param seqGate          业务号发号器，非空；告警号 AL 取号出口
     * @param events           Spring 事件发布器，非空；域事件事务内发布入口（AFTER_COMMIT 出 MQ）
     * @param pushService      STOMP 推送服务，非空；告警 WS 推送出口
     * @param stormGuard       风暴抑制器，非空；抑制①③④判定执行点
     * @param offlineDetector  离线探测器，非空；离线规则源候选扫描
     * @param redisTemplate    String 模板（A.5-1），非空；越限回合标记与最新值快照通道
     * @param properties       告警引擎配置属性，非空；快照 TTL 等参数
     * @param transactionManager 平台事务管理器，非空；仅用于构建单条告警落行的 REQUIRES_NEW
     *                           独立事务模板（perAlarmTx，PG 25P02 冲突隔离——见字段注记）
     */
    public AlarmEngine(
            IotAlarmRuleMapper ruleMapper,
            IotAlarmMapper alarmMapper,
            IotDeviceMapper deviceMapper,
            IBindingService bindingService,
            IotSeqGate seqGate,
            ApplicationEventPublisher events,
            ITelemetryPushService pushService,
            StormGuard stormGuard,
            OfflineDetector offlineDetector,
            StringRedisTemplate redisTemplate,
            AlarmProperties properties,
            PlatformTransactionManager transactionManager) {
        this.ruleMapper = ruleMapper;
        this.alarmMapper = alarmMapper;
        this.deviceMapper = deviceMapper;
        this.bindingService = bindingService;
        this.seqGate = seqGate;
        this.events = events;
        this.pushService = pushService;
        this.stormGuard = stormGuard;
        this.offlineDetector = offlineDetector;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // 单条落行须挂起外层批事务独立提交：PG 唯一冲突中止的是本条事务（25P02），不污染同批评估
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perAlarmTx = template;
    }

    /**
     * 遥测批次评估输入载体（brief 冻结签名 evaluate(TelemetryBatch(batch)) 的批次形态）。
     *
     * @param rows 本批已落库遥测实体（含唯一键冲突忽略行），非空
     */
    public record TelemetryBatch(List<IotTelemetryEntity> rows) {}

    /**
     * 遥测批次评估入口（阈值源 + 离线源 + 升级惰性扫描 + 快照补写 + 补推排空）。
     *
     * <p>执行流程：①最新值快照补写（实测 fy:iot:snapshot:latest 无写入方，自 ingest 链补齐，
     * TTL ≥2×采集周期）→②阈值规则逐条评估（越限回合状态机 + 触发链）→③离线规则源惰性扫描
     * →④抑制⑤升级惰性扫描→⑤风暴解除补推排空（随规则评估顺带执行）。全程独立短事务：
     * 新告警落行与事件发布同事务，WS 推送挂事务 afterCommit。传播强制 REQUIRES_NEW：调用点
     * ingest afterCommit 阶段原事务已提交但同步上下文仍激活（Spring afterCommit javadoc 场景），
     * REQUIRED 会加入死事务致 AFTER_COMMIT 监听与推送同步双双失联——见类注释「事件与推送时机」。
     *
     * @param batch 本批已落库遥测实体批次，非空；来源：TelemetryIngestServiceImpl afterCommit
     *              （事务提交后调起，不阻塞 ingest 事务）；空批次直接返回
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(TelemetryBatch batch) {
        // 空批次防御：不触库不触 Redis（防空 IN 列表与无意义 Redis 往返）
        if (batch == null || batch.rows().isEmpty()) {
            return;
        }
        List<IotTelemetryEntity> rows = batch.rows();
        Map<String, IotTelemetryEntity> latestRowByKey = latestRowByDeviceAndMetric(rows);
        writeLatestSnapshots(latestRowByKey);
        evaluateThresholdRules(latestRowByKey);
        evaluateOfflineSource();
        scanEscalations();
    }

    /**
     * 设备告警透传评估入口（透传规则源）：device.alarm 帧命中 DEVICE_ALARM 规则走统一触发链。
     *
     * <p>单帧路径（消费线程直入，非批量）：绑定快照与设备档案单查富化；多规则命中取 id 最小
     * 单条（确定性优先，防同帧多发）；未命中规则 info 跳过（透传规则未登记的告警帧不产生告警行）。
     *
     * @param frame 设备告警帧解析产物，非空；来源：TelemetryFrameParser 第四形态解析产物
     *              （AMQP 消费线程调起）
     */
    @Transactional
    public void evaluateDeviceAlarm(DeviceAlarmFrame frame) {
        // 数据库读操作：DEVICE_ALARM 型启用规则单查（规则量小，无分页面）
        List<IotAlarmRuleEntity> rules = loadRules(AlarmRuleType.DEVICE_ALARM);
        IotAlarmRuleEntity matched = rules.stream()
                .filter(rule -> rule.getDeviceId() == null || rule.getDeviceId().equals(frame.deviceId()))
                .filter(rule -> frame.metricCode() != null && frame.metricCode().equals(rule.getMetricCode()))
                .min(Comparator.comparing(IotAlarmRuleEntity::getId))
                .orElse(null);
        if (matched == null) {
            // 未命中透传规则：info 跳过（透传规则未登记不产生告警行，非异常路径）
            log.info(
                    "设备告警帧未命中透传规则，跳过：deviceId={}，alarm={}，severity={}",
                    frame.deviceId(),
                    frame.metricCode(),
                    frame.severity());
            return;
        }
        // 绑定快照与设备档案单查富化（单帧路径非批量链路）
        BindingVO binding = bindingService.findActiveByDevice(frame.deviceId()).orElse(null);
        IotDeviceEntity device = deviceMapper.selectById(frame.deviceId());
        // 触发值以告警描述承载（保留原始形态；缺失回退 severity 留痕）
        String triggerValue =
                frame.description() != null && !frame.description().isBlank()
                        ? frame.description()
                        : String.valueOf(frame.severity());
        fireNewAlarm(
                matched,
                frame.deviceId(),
                binding,
                device,
                matched.getMetricCode(),
                triggerValue,
                frame.occurredAt(),
                true);
    }

    /**
     * 阈值规则评估：每条规则按「设备+指标」最新行逐设备核算越限回合状态机。
     *
     * <p>回合状态机（Redis 标记承载，批次频度核算）：值未越阈值（或回落恢复带内）→ 复位回合
     * （删标记）；首越限 → 置标记起算持续时长；持续达标（标记时点距今 ≥ duration_secs）→ 触发
     * （抑制链裁决新发或聚合）并清标记（下一回合需重新起算）。
     *
     * @param latestRowByKey 设备+指标 → 本批最新行映射，非空
     */
    private void evaluateThresholdRules(Map<String, IotTelemetryEntity> latestRowByKey) {
        List<IotAlarmRuleEntity> rules = loadRules(AlarmRuleType.THRESHOLD);
        if (rules.isEmpty()) {
            return;
        }
        // 批级快照装载（宪法 A.4.3-14）：绑定快照与设备档案各一次 IN 查询，供触发链富化路由
        Set<String> deviceIds = new LinkedHashSet<>();
        latestRowByKey.values().forEach(row -> deviceIds.add(row.getDeviceId()));
        Map<String, BindingVO> bindingByDevice = loadBindings(deviceIds);
        Map<String, IotDeviceEntity> deviceByDeviceId = loadDevices(deviceIds);
        Instant now = Instant.now();
        for (IotAlarmRuleEntity rule : rules) {
            // 抑制④解除补推：每规则评估前顺带排空遗留队列（风暴已解除时）
            pushDeferredIfStormCleared(rule);
            for (IotTelemetryEntity row : latestRowByKey.values()) {
                if (!isRuleMatched(rule, row)) {
                    continue;
                }
                evaluateThresholdForDevice(
                        rule,
                        row,
                        bindingByDevice.get(row.getDeviceId()),
                        deviceByDeviceId.get(row.getDeviceId()),
                        now);
            }
        }
    }

    /**
     * 单设备阈值核算（回合状态机 + 触发链）。
     *
     * @param rule    命中阈值规则，非空
     * @param row     该设备该指标本批最新行，非空
     * @param binding 绑定快照，可空（无绑定：病区经设备档案兜底）
     * @param device  设备档案，可空（档案在册才可兜底病区）
     * @param now     评估基准时刻（批内单次采样），非空
     */
    private void evaluateThresholdForDevice(
            IotAlarmRuleEntity rule, IotTelemetryEntity row, BindingVO binding, IotDeviceEntity device, Instant now) {
        BigDecimal value = row.getValue();
        if (value == null) {
            // 非数值行不进阈值判定（W-7 口径：value 落 NULL 的行无越限语义）
            return;
        }
        String breachKey = BREACH_KEY_PREFIX + rule.getId() + ":" + row.getDeviceId() + ":" + rule.getMetricCode();
        if (!isBeyondThreshold(rule, value)) {
            // 值回到阈值内：回合复位（删标记，恢复带内不重复触发的复位面）
            deleteBreachMarker(breachKey);
            return;
        }
        Instant episodeStart = readBreachMarker(breachKey);
        if (episodeStart == null) {
            // 首越限：置标记起算持续时长，不触发
            writeBreachMarker(breachKey, now, rule.getDurationSecs());
            return;
        }
        int durationSecs = rule.getDurationSecs() == null ? 0 : rule.getDurationSecs();
        if (Duration.between(episodeStart, now).getSeconds() < durationSecs) {
            // 持续时长未达标：静默等待（保留标记继续累计）
            return;
        }
        // 持续达标：清标记（触发后回合归零，重复触发需重新起算——聚合面由抑制①承接）
        deleteBreachMarker(breachKey);
        // 恢复带内不重复触发：活跃告警在挂且值仍在恢复带边界内 → 忽略本次聚合计数（防阈值附近抖动反复计数）
        if (stormGuard.hasActiveAlarm(rule.getId(), row.getDeviceId()) && isWithinRecoveryBand(rule, value)) {
            log.info(
                    "恢复带内不重复触发：活跃告警在挂且值处于恢复带边界内，忽略本次计数：ruleId={}，deviceId={}，value={}",
                    rule.getId(),
                    row.getDeviceId(),
                    value.toPlainString());
            return;
        }
        fireNewAlarm(
                rule,
                row.getDeviceId(),
                binding,
                device,
                rule.getMetricCode(),
                value.toPlainString(),
                row.getOccurredAt().toInstant(),
                true);
    }

    /**
     * 离线规则源评估：探测器候选经统一触发链落告警（离线源自身不受抑制③约束）。
     */
    private void evaluateOfflineSource() {
        List<OfflineDetector.Candidate> candidates = offlineDetector.detect();
        if (candidates.isEmpty()) {
            return;
        }
        // 批级快照装载（宪法 A.4.3-14）：候选设备集合一次 IN 查询绑定快照
        Set<String> deviceIds = new LinkedHashSet<>();
        candidates.forEach(candidate -> deviceIds.add(candidate.device().getDeviceId()));
        Map<String, BindingVO> bindingByDevice = loadBindings(deviceIds);
        for (OfflineDetector.Candidate candidate : candidates) {
            IotDeviceEntity device = candidate.device();
            Instant occurredAt =
                    device.getLastOnlineAt() != null ? device.getLastOnlineAt().toInstant() : Instant.now();
            // 触发值以最后在线时刻承载（离线告警的判定证据留痕）
            fireNewAlarm(
                    candidate.rule(),
                    device.getDeviceId(),
                    bindingByDevice.get(device.getDeviceId()),
                    device,
                    OFFLINE_METRIC_CODE,
                    String.valueOf(device.getLastOnlineAt()),
                    occurredAt,
                    false);
        }
    }

    /**
     * 抑制⑤升级惰性扫描（读时判定照 inpatient 会诊逾期先例，随 evaluate 调起）：ACTIVE+CRITICAL
     * 候选逐行核算升级时限，越限且未确认 → CAS 升级（escalation_count 旧值限定防重发）+ 发布
     * iot.alarm.escalated 动作事件（升级为动作广播非状态迁移，状态停留 ACTIVE）。
     */
    private void scanEscalations() {
        // 数据库读操作：升级候选扫描（mapper @Select，条件内嵌 deleted=0，idx 准入）
        List<IotAlarmEntity> candidates = alarmMapper.selectCriticalActive();
        if (candidates.isEmpty()) {
            return;
        }
        // 规则批量装载（宪法 A.4.3-14）：候选行规则 id 去重一次取回升级时限配置
        List<Long> ruleIds =
                candidates.stream().map(IotAlarmEntity::getRuleId).distinct().toList();
        Map<Long, IotAlarmRuleEntity> ruleById = new HashMap<>(ruleIds.size());
        for (IotAlarmRuleEntity rule : ruleMapper.selectBatchIds(ruleIds)) {
            ruleById.put(rule.getId(), rule);
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        for (IotAlarmEntity alarm : candidates) {
            IotAlarmRuleEntity rule = ruleById.get(alarm.getRuleId());
            if (rule == null) {
                // 规则已删：活跃告警无升级配置来源，跳过（不误伤告警行本身）
                continue;
            }
            // 升级时限锚：首次升级以触发时刻（created_at）起算，再次升级以最近升级时刻起算
            OffsetDateTime anchor =
                    alarm.getLastEscalatedAt() != null ? alarm.getLastEscalatedAt() : alarm.getCreatedAt();
            if (anchor == null) {
                continue;
            }
            int escalateAfterSecs = rule.getEscalateAfterSecs() == null ? 300 : rule.getEscalateAfterSecs();
            if (now.toInstant().isBefore(anchor.toInstant().plusSeconds(escalateAfterSecs))) {
                continue;
            }
            // 数据库写操作：升级 CAS（旧值限定兜底并发双读——仅首个升级方发布，DB 防重发）
            if (alarmMapper.casEscalate(
                            alarm.getId(),
                            alarm.getEscalationCount() == null ? 0 : alarm.getEscalationCount(),
                            StormGuard.SYSTEM_OPERATOR)
                    == 0) {
                continue;
            }
            int escalationLevel = (alarm.getEscalationCount() == null ? 0 : alarm.getEscalationCount()) + 1;
            // 消息发送：事务内发布升级动作事件（AFTER_COMMIT 出 MQ，宪法 A.4.2-7）
            events.publishEvent(new IotDomainEvent(
                    IotMessagingConstants.EVENT_ALARM_ESCALATED,
                    new AlarmEscalatedPayload(
                            alarm.getAlarmNo(),
                            alarm.getDeviceId(),
                            alarm.getWardId(),
                            escalationLevel,
                            now.toInstant()),
                    now.toInstant(),
                    currentTraceId()));
            log.warn(
                    "危急告警升级动作已执行（状态停留 ACTIVE）：alarmNo={}，wardId={}，escalationLevel={}，escalateAfterSecs={}",
                    alarm.getAlarmNo(),
                    alarm.getWardId(),
                    escalationLevel,
                    escalateAfterSecs);
        }
    }

    /**
     * 统一新发触发链（三类源共用）：抑制①③裁决 → 抑制④风暴计数 → 落行 → 事件发布 → 推送分派。
     * 落行+事件发布+推送/补推登记收进单条 REQUIRES_NEW 独立事务（perAlarmTx）：并发唯一冲突
     * （uk_iot_alarm_active 兜底）幂等跳过仅丢单条告警，不污染批事务致同批连带回滚。
     *
     * @param rule             命中规则，非空
     * @param deviceId         触发源设备号，非空
     * @param binding          绑定快照，可空（无绑定病区经设备档案兜底）
     * @param device           设备档案，可空
     * @param metricCode       告警指标编码，非空（离线源为 DEVICE_OFFLINE 固定值）
     * @param triggerValue     触发值原文，非空（超列宽截断留痕）
     * @param occurredAt       业务发生时刻，非空
     * @param telemetryDerived true=遥测衍生（受抑制③约束）；false=离线规则源自身
     */
    private void fireNewAlarm(
            IotAlarmRuleEntity rule,
            String deviceId,
            BindingVO binding,
            IotDeviceEntity device,
            String metricCode,
            String triggerValue,
            Instant occurredAt,
            boolean telemetryDerived) {
        // 抑制①③联合裁决：聚合命中/离线抑制即止（计数/跳过已在 StormGuard 留痕）
        StormGuard.TriggerOutcome outcome =
                stormGuard.decide(rule.getId(), deviceId, StormGuard.SYSTEM_OPERATOR, telemetryDerived);
        if (outcome instanceof StormGuard.TriggerOutcome.Aggregated) {
            return;
        }
        if (outcome instanceof StormGuard.TriggerOutcome.OfflineSuppressed) {
            return;
        }
        // 抑制④：触发计数+1 并判定风暴态（置位在 StormGuard 内完成）
        boolean storm = stormGuard.recordTriggerAndCheckStorm(rule.getId());
        // 病区路由：绑定快照优先，设备档案兜底；双缺跳过新发（无法定推 WS 主题，warn 留痕）
        Long wardId = resolveWardId(binding, device);
        if (wardId == null) {
            log.warn(
                    "{}：ruleId={}，deviceId={}，alarmLevel={}",
                    WARD_UNRESOLVED_SKIP_LOG,
                    rule.getId(),
                    deviceId,
                    rule.getAlarmLevel());
            return;
        }
        String alarmNo = seqGate.nextAlarmNo();
        IotAlarmEntity entity =
                buildAlarmEntity(rule, deviceId, binding, wardId, metricCode, triggerValue, occurredAt, alarmNo);
        try {
            // 单条告警独立短事务（perAlarmTx REQUIRES_NEW，挂起外层批事务）：落行 + 事务内发布
            // triggered 事件 + 推送/补推登记收进同事务——PG 下唯一索引冲突即中止当前事务（25P02），
            // 冲突经异常出栈仅回滚本条（catch 在模板外承接），同批其余「规则×设备」评估与升级扫描
            // 不连带回滚
            perAlarmTx.executeWithoutResult(txStatus -> {
                // 数据库写操作：告警落行（同事务发 triggered 事件——AFTER_COMMIT 出 MQ 由发布器承载）
                alarmMapper.insert(entity);
                events.publishEvent(new IotDomainEvent(
                        IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                        new AlarmTriggeredPayload(
                                alarmNo,
                                deviceId,
                                entity.getPatientId(),
                                entity.getVisitId(),
                                wardId,
                                rule.getAlarmLevel().getCode(),
                                metricCode,
                                entity.getTriggerValue(),
                                rule.getId(),
                                occurredAt),
                        occurredAt,
                        currentTraceId()));
                log.info(
                        "告警已落行并发布触发事件：alarmNo={}，ruleId={}，deviceId={}，wardId={}，level={}，metric={}，triggerValue={}，storm={}",
                        alarmNo,
                        rule.getId(),
                        deviceId,
                        wardId,
                        rule.getAlarmLevel(),
                        metricCode,
                        entity.getTriggerValue(),
                        storm);
                if (storm && rule.getAlarmLevel() != AlarmLevel.CRITICAL) {
                    // 抑制④：风暴期非危急只入库不推 WS，告警号入补推队列（解除后排空补推）
                    stormGuard.queueDeferredPush(rule.getId(), alarmNo);
                    return;
                }
                pushAfterCommit(entity);
            });
        } catch (DuplicateKeyException e) {
            // 并发新发窗口命中部分唯一索引 uk_iot_alarm_active（抑制①的 DB 兜底）：幂等跳过
            log.warn("并发新发命中活跃唯一索引（uk_iot_alarm_active 兜底），按聚合计数语义幂等跳过：ruleId={}，deviceId={}", rule.getId(), deviceId);
            return;
        }
    }

    /**
     * 风暴解除补推排空：规则评估前顺带执行（风暴标记不在挂且补推队列有遗留时，按号取回告警行
     * 逐条补推 WS）。
     *
     * @param rule 待评估规则，非空
     */
    private void pushDeferredIfStormCleared(IotAlarmRuleEntity rule) {
        List<String> pendingAlarmNos = stormGuard.drainDeferredIfStormCleared(rule.getId());
        if (pendingAlarmNos.isEmpty()) {
            return;
        }
        // 数据库读操作：补推告警号集合批量取回（宪法 A.4.3-14，自然键 alarm_no IN）
        List<IotAlarmEntity> deferredAlarms = alarmMapper.selectList(
                Wrappers.<IotAlarmEntity>lambdaQuery().in(IotAlarmEntity::getAlarmNo, pendingAlarmNos));
        deferredAlarms.forEach(this::pushAfterCommit);
        log.info("风暴解除补推完成：ruleId={}，count={}", rule.getId(), deferredAlarms.size());
    }

    /**
     * 最新值快照补写（阈值评估辅助面）：每设备每指标一次 SETEX（TTL ≥2×采集周期，配置承载）。
     * 快照消费方为排障与后续查询面（阈值判定主证据为本批行），写失败降级不阻断评估链。
     *
     * @param latestRowByKey 设备+指标 → 本批最新行映射，非空
     */
    private void writeLatestSnapshots(Map<String, IotTelemetryEntity> latestRowByKey) {
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            for (IotTelemetryEntity row : latestRowByKey.values()) {
                if (row.getValue() == null) {
                    // 非数值行不进快照（阈值评估面仅数值语义）
                    continue;
                }
                // Redis 写操作：最新值快照（值|发生时刻毫秒 管道文本承载，TTL 配置化）
                ops.set(
                        LATEST_SNAPSHOT_KEY_PREFIX + row.getDeviceId() + ":" + row.getMetricCode(),
                        row.getValue().toPlainString() + "|"
                                + row.getOccurredAt().toInstant().toEpochMilli(),
                        properties.latestSnapshotTtl());
            }
        } catch (RuntimeException e) {
            // Redis 降级：快照为辅助面，写失败留痕不阻断评估链
            log.warn("最新值快照写失败（辅助面降级，不阻断评估）：原因={}", e.getMessage());
        }
    }

    /**
     * 规则匹配判定：指标编码精确相等 + 设备号范围（规则未限定设备 = 全部设备命中）。
     *
     * @param rule 阈值规则，非空
     * @param row  遥测行，非空
     * @return true=该行参与本规则评估
     */
    private static boolean isRuleMatched(IotAlarmRuleEntity rule, IotTelemetryEntity row) {
        return rule.getMetricCode() != null
                && rule.getMetricCode().equals(row.getMetricCode())
                && (rule.getDeviceId() == null || rule.getDeviceId().equals(row.getDeviceId()));
    }

    /**
     * 越限判定（严格阈值比较）：GT=值&gt;阈值；LT=值&lt;阈值（BigDecimal compareTo，等值不算越限）。
     *
     * @param rule  阈值规则，非空（compare_op/threshold_value 缺失行按抖动防护②拒保存约束应不存在）
     * @param value 采集值，非空
     * @return true=越限
     */
    private static boolean isBeyondThreshold(IotAlarmRuleEntity rule, BigDecimal value) {
        if (rule.getCompareOp() == null || rule.getThresholdValue() == null) {
            return false;
        }
        int compared = value.compareTo(rule.getThresholdValue());
        return rule.getCompareOp() == ThresholdOp.GT ? compared > 0 : compared < 0;
    }

    /**
     * 恢复带边界判定：GT=值 ≤ 阈值+恢复带；LT=值 ≥ 阈值-恢复带（活跃告警在挂时，边界内的重复
     * 越限不聚合计数——恢复带内不重复触发）。
     *
     * @param rule  阈值规则，非空（recovery_band 缺失按抖动防护②拒保存约束应不存在）
     * @param value 采集值，非空
     * @return true=值处于恢复带边界内
     */
    private static boolean isWithinRecoveryBand(IotAlarmRuleEntity rule, BigDecimal value) {
        if (rule.getCompareOp() == null || rule.getThresholdValue() == null || rule.getRecoveryBand() == null) {
            return false;
        }
        BigDecimal boundary = rule.getCompareOp() == ThresholdOp.GT
                ? rule.getThresholdValue().add(rule.getRecoveryBand())
                : rule.getThresholdValue().subtract(rule.getRecoveryBand());
        return rule.getCompareOp() == ThresholdOp.GT ? value.compareTo(boundary) <= 0 : value.compareTo(boundary) >= 0;
    }

    /**
     * 越限回合标记读取：标记值承载回合起算时刻（ISO-8601 文本）。
     *
     * @param breachKey 回合标记键，非空
     * @return 回合起算时刻；标记缺席或解析失败返回 null（解析失败按缺席复位，warn 留痕）
     */
    private Instant readBreachMarker(String breachKey) {
        try {
            String marker = redisTemplate.opsForValue().get(breachKey);
            return marker == null || marker.isBlank() ? null : Instant.parse(marker);
        } catch (RuntimeException e) {
            // Redis 降级：回合标记读取失败按缺席处理（warn 留痕，跳过本回合评估）
            log.warn("越限回合标记读取失败（按缺席复位）：key={}，原因={}", breachKey, e.getMessage());
            return null;
        }
    }

    /**
     * 越限回合标记写入：TTL=持续时长+60s 余量（覆盖批次间隔抖动）。
     *
     * @param breachKey    回合标记键，非空
     * @param startedAt    回合起算时刻，非空
     * @param durationSecs 规则持续时长秒（可空按 0），可空
     */
    private void writeBreachMarker(String breachKey, Instant startedAt, Integer durationSecs) {
        try {
            long ttlSeconds = (durationSecs == null ? 0 : durationSecs) + BREACH_TTL_GRACE.getSeconds();
            redisTemplate.opsForValue().set(breachKey, startedAt.toString(), Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            // Redis 降级：标记写入失败本回合无法累计持续时长，warn 留痕（下批重试起算）
            log.warn("越限回合标记写入失败（本回合起算缺失）：key={}，原因={}", breachKey, e.getMessage());
        }
    }

    /**
     * 越限回合标记删除（回合复位）。
     *
     * @param breachKey 回合标记键，非空
     */
    private void deleteBreachMarker(String breachKey) {
        try {
            redisTemplate.delete(breachKey);
        } catch (RuntimeException e) {
            // Redis 降级：复位失败仅多一轮标记存续（值回落面下批再清），warn 留痕
            log.warn("越限回合标记删除失败（下批重试复位）：key={}，原因={}", breachKey, e.getMessage());
        }
    }

    /**
     * WS 推送时机分派（宪法 A.4.2-7）：有事务同步上下文挂 afterCommit（评估事务提交后执行），
     * 无上下文（单测直调等未经代理场景）直推行为不变；推送失败吞并 error 留痕（辅助语义）。
     *
     * @param entity 已落库告警实体，非空
     */
    private void pushAfterCommit(IotAlarmEntity entity) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务同步激活：挂 afterCommit，评估事务提交后执行推送（回滚事务不推送）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    pushAlarmQuietly(entity);
                }
            });
        } else {
            pushAlarmQuietly(entity);
        }
    }

    /**
     * 执行告警 WS 推送（失败吞并：推送是辅助语义，落行与事件不受影响）。
     *
     * @param entity 已落库告警实体，非空
     */
    private void pushAlarmQuietly(IotAlarmEntity entity) {
        try {
            pushService.pushAlarm(entity);
        } catch (RuntimeException e) {
            log.error("告警 WS 推送失败（不影响落行与事件）：alarmNo={}，原因={}", entity.getAlarmNo(), e.getMessage(), e);
        }
    }

    /**
     * 组装告警实体（绑定快照五元组冗余 + 状态初态 ACTIVE + 触发计数 1）。
     *
     * @param rule         命中规则，非空
     * @param deviceId     触发源设备号，非空
     * @param binding      绑定快照，可空
     * @param wardId       已解析病区 ID，非空
     * @param metricCode   告警指标编码，非空
     * @param triggerValue 触发值原文，非空（超列宽截断）
     * @param occurredAt   业务发生时刻，非空
     * @param alarmNo      告警业务号，非空
     * @return 告警实体，非空
     */
    private static IotAlarmEntity buildAlarmEntity(
            IotAlarmRuleEntity rule,
            String deviceId,
            BindingVO binding,
            Long wardId,
            String metricCode,
            String triggerValue,
            Instant occurredAt,
            String alarmNo) {
        IotAlarmEntity entity = new IotAlarmEntity();
        entity.setAlarmNo(alarmNo);
        entity.setRuleId(rule.getId());
        entity.setDeviceId(deviceId);
        // 步骤五患者关联同款口径：绑定快照冗余患者/就诊，无绑定落 NULL
        entity.setPatientId(binding == null ? null : binding.patientId());
        entity.setVisitId(binding == null ? null : binding.visitId());
        entity.setWardId(wardId);
        entity.setAlarmLevel(rule.getAlarmLevel());
        entity.setMetricCode(metricCode);
        entity.setTriggerValue(truncateTriggerValue(triggerValue));
        entity.setStatus(AlarmStatus.ACTIVE);
        entity.setTriggerCount(1);
        entity.setLastTriggeredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        entity.setEscalationCount(0);
        entity.setTraceId(currentTraceId());
        return entity;
    }

    /**
     * 触发值列宽防线：超 VARCHAR(255) 截断（原始形态留证优先保留前段）。
     *
     * @param triggerValue 触发值原文，可空
     * @return 列宽内文本，非空
     */
    private static String truncateTriggerValue(String triggerValue) {
        if (triggerValue == null) {
            return "";
        }
        return triggerValue.length() <= TRIGGER_VALUE_MAX_LENGTH
                ? triggerValue
                : triggerValue.substring(0, TRIGGER_VALUE_MAX_LENGTH);
    }

    /**
     * 病区路由解析：绑定快照病区优先，设备档案病区兜底。
     *
     * @param binding 绑定快照，可空
     * @param device  设备档案，可空
     * @return 病区 ID；双缺返回 null（调用方跳过新发）
     */
    private static Long resolveWardId(BindingVO binding, IotDeviceEntity device) {
        if (binding != null && binding.wardId() != null) {
            return binding.wardId();
        }
        return device == null ? null : device.getWardId();
    }

    /**
     * 按类型装载启用规则（@TableLogic 自动携带 deleted=0，enabled=true 过滤禁用规则）。
     *
     * @param type 规则类型，非空
     * @return 启用规则清单，非空
     */
    private List<IotAlarmRuleEntity> loadRules(AlarmRuleType type) {
        // 数据库读操作：启用规则单查（idx_iot_alarm_rule_type_enabled 准入）
        return ruleMapper.selectList(Wrappers.<IotAlarmRuleEntity>lambdaQuery()
                .eq(IotAlarmRuleEntity::getRuleType, type)
                .eq(IotAlarmRuleEntity::getEnabled, true));
    }

    /**
     * 绑定快照批查询（宪法 A.4.3-14）：设备集合一次 IN 查询组装 deviceId → 快照映射。
     *
     * @param deviceIds 设备号集合，非空（可为空集合——返回空映射）
     * @return deviceId → BOUND 绑定快照映射，非空
     */
    private Map<String, BindingVO> loadBindings(Set<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        // 数据库读操作：单次 IN 批量取 BOUND 生效绑定
        Map<String, BindingVO> bindingByDevice = new HashMap<>(deviceIds.size());
        for (BindingVO binding : bindingService.listActiveByDevices(deviceIds)) {
            bindingByDevice.put(binding.deviceId(), binding);
        }
        return bindingByDevice;
    }

    /**
     * 设备档案批查询（宪法 A.4.3-14）：设备集合一次 IN 查询（病区兜底路由输入）。
     *
     * @param deviceIds 设备号集合，非空
     * @return deviceId → 设备档案映射，非空
     */
    private Map<String, IotDeviceEntity> loadDevices(Set<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        // 数据库读操作：单次 IN 批量取设备档案
        Map<String, IotDeviceEntity> deviceByDeviceId = new HashMap<>(deviceIds.size());
        for (IotDeviceEntity device : deviceMapper.selectList(
                Wrappers.<IotDeviceEntity>lambdaQuery().in(IotDeviceEntity::getDeviceId, deviceIds))) {
            deviceByDeviceId.put(device.getDeviceId(), device);
        }
        return deviceByDeviceId;
    }

    /**
     * 设备+指标 → 本批最新行映射（快照补写与阈值评估共用，纯内存归并）。
     *
     * @param rows 遥测行批次，非空
     * @return deviceId:metricCode → occurredAt 最新行，非空
     */
    private static Map<String, IotTelemetryEntity> latestRowByDeviceAndMetric(List<IotTelemetryEntity> rows) {
        Map<String, IotTelemetryEntity> latest = new HashMap<>(rows.size());
        for (IotTelemetryEntity row : rows) {
            String key = row.getDeviceId() + ":" + row.getMetricCode();
            IotTelemetryEntity existing = latest.get(key);
            if (existing == null || row.getOccurredAt().isAfter(existing.getOccurredAt())) {
                latest.put(key, row);
            }
        }
        return latest;
    }

    /**
     * 发布点捕获 traceId（AFTER_COMMIT 回调执行时 MDC 不可依赖——构造事件时捕获透传）。
     *
     * @return 当前线程 traceId；无日志上下文为 null（信封契约允许）
     */
    private static String currentTraceId() {
        return MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY);
    }
}

package com.fuyun.iot.internal.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.fuyun.iot.enums.DeviceStatus;
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
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 告警引擎单元测试（FU-M14-08，P2 PR-2 Task 7 Step 2）：三类规则源逐项触发与五项风暴抑制逐项——
 * ①同源聚合计数（CAS 命中活跃行不新发）、③离线抑制衍生遥测告警、④风暴态置位与解除补推、
 * ⑤危急升级动作发布（读时惰性）；阈值源持续时长/恢复带状态机；离线源 DEVICE_OFFLINE 告警；
 * 透传源 device.alarm 帧命中规则；最新值快照补写（实测 fy:iot:snapshot:latest 缺失面）。
 *
 * <p>StormGuard 以真实实例接入共享 mock（alarmMapper/ruleMapper/redisTemplate），抑制链路按
 * 组件间真实协作驱动；mapper 真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class AlarmEngineTest {

    private static final String DEVICE_ID = "dev-001";

    private static final String METRIC_CODE = "MDC_ECG_HEART_RATE";

    private static final long RULE_ID = 900001L;

    /** 越限回合标记键形态（GC13 A.5-1 命名：fy:iot:alarm:breach:{ruleId}:{deviceId}:{metricCode}） */
    private static final String BREACH_KEY = "fy:iot:alarm:breach:" + RULE_ID + ":" + DEVICE_ID + ":" + METRIC_CODE;

    /** 最新值快照键形态（brief 冻结：fy:iot:snapshot:latest:{deviceId}:{metricCode}） */
    private static final String SNAPSHOT_KEY = "fy:iot:snapshot:latest:" + DEVICE_ID + ":" + METRIC_CODE;

    /** 快照 TTL（≥2×采集周期，配置默认 10 分钟） */
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);

    /** 风暴标记键形态（brief 冻结：fy:iot:alarm:storm:{ruleId}） */
    private static final String STORM_KEY = "fy:iot:alarm:storm:" + RULE_ID;

    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");

    private static final AlarmProperties PROPERTIES = new AlarmProperties(Duration.ofMinutes(10), 50, SNAPSHOT_TTL);

    @Mock
    private IotAlarmRuleMapper ruleMapper;

    @Mock
    private IotAlarmMapper alarmMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IBindingService bindingService;

    @Mock
    private IotSeqGate seqGate;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private ITelemetryPushService pushService;

    @Mock
    private OfflineDetector offlineDetector;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<IotAlarmEntity> alarmCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    private AlarmEngine engine;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotAlarmRuleEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotAlarmEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotTelemetryEntity.class);
    }

    @BeforeEach
    void setUp() {
        engine = new AlarmEngine(
                ruleMapper,
                alarmMapper,
                deviceMapper,
                bindingService,
                seqGate,
                events,
                pushService,
                // StormGuard 以真实实例接入共享 mock：抑制链路按组件间真实协作驱动
                new StormGuard(alarmMapper, ruleMapper, redisTemplate, PROPERTIES),
                offlineDetector,
                redisTemplate,
                PROPERTIES,
                // mock 事务管理器：perAlarmTx 回调直通执行（无同步上下文，推送直推同既有用例口径）
                transactionManager);
    }

    @Test
    @DisplayName("阈值源：首次越限仅起算持续时长（置回合标记），不落告警不发布")
    void firstBreachArmsEpisodeWithoutTrigger() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 回合标记缺席 = 首次观测越限：仅置标记起算，不触发
        when(valueOperations.get(BREACH_KEY)).thenReturn(null);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        // 首越限起算标记：TTL = 持续时长 30s + 60s 兜底余量
        verify(valueOperations).set(eq(BREACH_KEY), anyString(), eq(Duration.ofSeconds(90)));
        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("阈值源：越限持续达标触发新发告警——落行+triggered 事件+WS 推送，绑定快照五元组冗余")
    void sustainedBreachCreatesAlarmPublishesAndPushes() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 回合标记已起算 60s（≥ 持续时长 30s）：触发
        when(valueOperations.get(BREACH_KEY)).thenReturn(NOW.minusSeconds(60).toString());
        when(bindingService.listActiveByDevices(anyCollection()))
                .thenReturn(List.of(binding(5L, "20260901000001", 1001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(1001L)));
        when(seqGate.nextAlarmNo()).thenReturn("AL2026092600001");
        when(alarmMapper.insert(any(IotAlarmEntity.class))).thenReturn(1);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(alarmMapper).insert(alarmCaptor.capture());
        IotAlarmEntity inserted = alarmCaptor.getValue();
        assertThat(inserted.getAlarmNo()).isEqualTo("AL2026092600001");
        assertThat(inserted.getRuleId()).isEqualTo(RULE_ID);
        assertThat(inserted.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(inserted.getPatientId()).isEqualTo(5L);
        assertThat(inserted.getVisitId()).isEqualTo("20260901000001");
        assertThat(inserted.getWardId()).isEqualTo(1001L);
        assertThat(inserted.getAlarmLevel()).isEqualTo(AlarmLevel.CRITICAL);
        assertThat(inserted.getMetricCode()).isEqualTo(METRIC_CODE);
        assertThat(inserted.getTriggerValue()).isEqualTo("170");
        assertThat(inserted.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(inserted.getTriggerCount()).isEqualTo(1);
        // 事件在事务内发布（AFTER_COMMIT 出 MQ 由发布器承载），载荷契约逐字段核对
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_TRIGGERED);
        AlarmTriggeredPayload payload = (AlarmTriggeredPayload) event.payload();
        assertThat(payload.alarmNo()).isEqualTo("AL2026092600001");
        assertThat(payload.wardId()).isEqualTo(1001L);
        assertThat(payload.ruleId()).isEqualTo(RULE_ID);
        // 无事务同步上下文（单测直调）：推送直推（生产经本事务 afterCommit 承接）
        verify(pushService).pushAlarm(inserted);
    }

    @Test
    @DisplayName("阈值源：值回落恢复带内复位回合（删标记），不触发")
    void recoveryResetsEpisode() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 值 120 未越限（阈值 150 方向 >）：回合复位（未越限路径不读标记，读面仅在越限时触达）

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(redisTemplate).delete(BREACH_KEY);
        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
    }

    @Test
    @DisplayName("阈值源：越限但持续时长未达标保持静默（不触发不删标记）")
    void durationNotReachedKeepsSilent() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 回合起算仅 10s（< 持续时长 30s）：静默等待（标记时点相对真实时钟，规避墙钟偏差）
        when(valueOperations.get(BREACH_KEY))
                .thenReturn(Instant.now().minusSeconds(10).toString());

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("抑制①：命中同源活跃告警行仅 CAS 聚合计数——不新发行不发布事件不推送")
    void aggregationCountsOnActiveAlarmWithoutNewRow() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(BREACH_KEY)).thenReturn(NOW.minusSeconds(60).toString());
        // 抑制① CAS 命中活跃行（返回 1 = 已聚合计数）
        when(alarmMapper.incrementTriggerIfActive(eq(RULE_ID), eq(DEVICE_ID), anyString()))
                .thenReturn(1);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(alarmMapper).incrementTriggerIfActive(eq(RULE_ID), eq(DEVICE_ID), anyString());
        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
        verify(pushService, never()).pushAlarm(any());
    }

    @Test
    @DisplayName("抑制③：设备已有 ACTIVE 离线告警时跳过其衍生遥测告警")
    void offlineActiveAlarmSuppressesDerivedTelemetryAlarm() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(BREACH_KEY)).thenReturn(NOW.minusSeconds(60).toString());
        // 设备已有活跃告警行，且其规则为 OFFLINE 型（衍生抑制判定双查）
        IotAlarmEntity activeOffline = new IotAlarmEntity();
        activeOffline.setRuleId(555L);
        when(alarmMapper.selectActiveByDevice(DEVICE_ID)).thenReturn(List.of(activeOffline));
        when(ruleMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(rule(555L, AlarmRuleType.OFFLINE, AlarmLevel.WARNING)));

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("抑制④：风暴态下非危急告警只入库不推 WS，告警号入补推队列")
    void stormSuppressesNonCriticalPushAndQueuesDeferred() {
        // 非危急规则（WARNING）：风暴期只入库不推送
        IotAlarmRuleEntity warningRule = rule(RULE_ID, AlarmRuleType.THRESHOLD, AlarmLevel.WARNING);
        warningRule.setMetricCode(METRIC_CODE);
        warningRule.setCompareOp(ThresholdOp.GT);
        warningRule.setThresholdValue(new BigDecimal("150"));
        warningRule.setDurationSecs(30);
        warningRule.setRecoveryBand(new BigDecimal("10"));
        when(ruleMapper.selectList(any())).thenReturn(List.of(warningRule));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(BREACH_KEY)).thenReturn(NOW.minusSeconds(60).toString());
        when(bindingService.listActiveByDevices(anyCollection()))
                .thenReturn(List.of(binding(5L, "20260901000001", 1001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(1001L)));
        when(seqGate.nextAlarmNo()).thenReturn("AL2026092600001");
        when(alarmMapper.insert(any(IotAlarmEntity.class))).thenReturn(1);
        // 抑制④：触发计数超基线（第 51 次 > 50）置风暴标记
        when(valueOperations.increment("fy:iot:alarm:rate:" + RULE_ID)).thenReturn(51L);
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(valueOperations).set(eq(STORM_KEY), eq("1"), eq(Duration.ofMinutes(10)));
        // 非危急风暴期不推 WS，入补推队列
        verify(pushService, never()).pushAlarm(any());
        verify(listOperations).rightPush("fy:iot:alarm:pending:" + RULE_ID, "AL2026092600001");
    }

    @Test
    @DisplayName("抑制④：风暴解除后补推队列排空推送（解除后补推）")
    void drainsDeferredPushAfterStormCleared() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule()));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 风暴已解除、补推队列有遗留：排空推送
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("fy:iot:alarm:pending:" + RULE_ID)).thenReturn(1L);
        when(listOperations.leftPop("fy:iot:alarm:pending:" + RULE_ID, 1L)).thenReturn(List.of("AL2026092600001"));
        IotAlarmEntity deferred = new IotAlarmEntity();
        deferred.setAlarmNo("AL2026092600001");
        deferred.setWardId(1001L);
        deferred.setAlarmLevel(AlarmLevel.WARNING);
        when(alarmMapper.selectList(any())).thenReturn(List.of(deferred));
        // 值未越限：本回合仅执行补推，不触发新告警（读标记面不触达）

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(pushService).pushAlarm(deferred);
        verify(redisTemplate).delete("fy:iot:alarm:pending:" + RULE_ID);
    }

    @Test
    @DisplayName("抑制④：OFFLINE 规则风暴解除后随评估统一排空补推队列推送（离线源排空洞口补齐）")
    void offlineRuleDrainsDeferredPushAfterStormCleared() {
        IotAlarmRuleEntity offlineRule = rule(666L, AlarmRuleType.OFFLINE, AlarmLevel.WARNING);
        when(ruleMapper.selectList(any())).thenReturn(List.of(offlineRule));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 风暴已解除、离线规则补推队列有遗留：统一排空面按启用规则覆盖离线源
        when(redisTemplate.hasKey("fy:iot:alarm:storm:666")).thenReturn(false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("fy:iot:alarm:pending:666")).thenReturn(1L);
        when(listOperations.leftPop("fy:iot:alarm:pending:666", 1L)).thenReturn(List.of("AL2026092600004"));
        IotAlarmEntity deferred = new IotAlarmEntity();
        deferred.setAlarmNo("AL2026092600004");
        deferred.setWardId(1001L);
        deferred.setAlarmLevel(AlarmLevel.WARNING);
        when(alarmMapper.selectList(any())).thenReturn(List.of(deferred));
        // 值未越限且无离线候选：本回合仅执行补推排空，不触发新告警

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(pushService).pushAlarm(deferred);
        verify(redisTemplate).delete("fy:iot:alarm:pending:666");
        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
    }

    @Test
    @DisplayName("抑制④：DEVICE_ALARM 规则风暴解除后随评估统一排空补推队列推送（透传源排空洞口补齐）")
    void deviceAlarmRuleDrainsDeferredPushAfterStormCleared() {
        IotAlarmRuleEntity passthrough = rule(777L, AlarmRuleType.DEVICE_ALARM, AlarmLevel.WARNING);
        passthrough.setMetricCode("deviceAlarmEvent");
        when(ruleMapper.selectList(any())).thenReturn(List.of(passthrough));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 风暴已解除、透传规则补推队列有遗留：统一排空面按启用规则覆盖透传源
        when(redisTemplate.hasKey("fy:iot:alarm:storm:777")).thenReturn(false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("fy:iot:alarm:pending:777")).thenReturn(1L);
        when(listOperations.leftPop("fy:iot:alarm:pending:777", 1L)).thenReturn(List.of("AL2026092600005"));
        IotAlarmEntity deferred = new IotAlarmEntity();
        deferred.setAlarmNo("AL2026092600005");
        deferred.setWardId(1001L);
        deferred.setAlarmLevel(AlarmLevel.WARNING);
        when(alarmMapper.selectList(any())).thenReturn(List.of(deferred));
        // 值未越限且无透传帧：本回合仅执行补推排空，不触发新告警

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(pushService).pushAlarm(deferred);
        verify(redisTemplate).delete("fy:iot:alarm:pending:777");
        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
    }

    @Test
    @DisplayName("抑制⑤：危急告警越升级时限未确认——CAS 升级并发布 escalated 事件（读时惰性）")
    void escalatesOverdueCriticalAlarm() {
        // 无阈值规则（selectList 缺省空清单）：本回合仅执行升级惰性扫描
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        IotAlarmEntity critical = new IotAlarmEntity();
        critical.setId(1L);
        critical.setAlarmNo("AL2026092500009");
        critical.setRuleId(RULE_ID);
        critical.setDeviceId(DEVICE_ID);
        critical.setWardId(1001L);
        critical.setAlarmLevel(AlarmLevel.CRITICAL);
        critical.setStatus(AlarmStatus.ACTIVE);
        critical.setEscalationCount(0);
        critical.setCreatedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(600), ZoneOffset.UTC));
        when(alarmMapper.selectCriticalActive()).thenReturn(List.of(critical));
        when(ruleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(rule()));
        when(alarmMapper.casEscalate(eq(1L), eq(0), anyString())).thenReturn(1);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        // 越限时点：created_at+300s < now → 升级档位 = 原次数+1
        verify(alarmMapper).casEscalate(eq(1L), eq(0), anyString());
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_ESCALATED);
        AlarmEscalatedPayload payload = (AlarmEscalatedPayload) event.payload();
        assertThat(payload.alarmNo()).isEqualTo("AL2026092500009");
        assertThat(payload.escalationLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("抑制⑤：未越升级时限不升级（CAS 不触发）")
    void skipsEscalationBeforeDeadline() {
        // 无阈值规则（selectList 缺省空清单）：本回合仅执行升级惰性扫描
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        IotAlarmEntity critical = new IotAlarmEntity();
        critical.setId(1L);
        critical.setRuleId(RULE_ID);
        critical.setAlarmLevel(AlarmLevel.CRITICAL);
        critical.setStatus(AlarmStatus.ACTIVE);
        critical.setEscalationCount(0);
        // 仅起算 100s（< 升级时限 300s）：未到期不升级（锚点相对真实时钟，规避墙钟偏差）
        critical.setCreatedAt(OffsetDateTime.ofInstant(Instant.now().minusSeconds(100), ZoneOffset.UTC));
        when(alarmMapper.selectCriticalActive()).thenReturn(List.of(critical));
        when(ruleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(rule()));

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(alarmMapper, never()).casEscalate(anyLong(), anyInt(), anyString());
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("离线源：ONLINE 设备最后在线超时新发 DEVICE_OFFLINE 告警（绑定快照路由病区）")
    void offlineDetectionCreatesDeviceOfflineAlarm() {
        // 无阈值规则（selectList 缺省空清单）：本回合仅离线源触发
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        IotAlarmRuleEntity offlineRule = rule(666L, AlarmRuleType.OFFLINE, AlarmLevel.WARNING);
        offlineRule.setOfflineSecs(600);
        IotDeviceEntity stalled = device(1001L);
        OffsetDateTime lastOnlineAt = OffsetDateTime.ofInstant(Instant.now().minusSeconds(700), ZoneOffset.UTC);
        stalled.setLastOnlineAt(lastOnlineAt);
        when(offlineDetector.detect()).thenReturn(List.of(new OfflineDetector.Candidate(offlineRule, stalled)));
        when(bindingService.listActiveByDevices(anyCollection()))
                .thenReturn(List.of(binding(5L, "20260901000001", 1001L)));
        when(seqGate.nextAlarmNo()).thenReturn("AL2026092600002");
        when(alarmMapper.insert(any(IotAlarmEntity.class))).thenReturn(1);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("120", NOW))));

        verify(alarmMapper).insert(alarmCaptor.capture());
        IotAlarmEntity inserted = alarmCaptor.getValue();
        assertThat(inserted.getMetricCode()).isEqualTo("DEVICE_OFFLINE");
        assertThat(inserted.getRuleId()).isEqualTo(666L);
        assertThat(inserted.getWardId()).isEqualTo(1001L);
        assertThat(inserted.getTriggerValue()).isEqualTo(String.valueOf(lastOnlineAt));
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_TRIGGERED);
        verify(pushService).pushAlarm(inserted);
    }

    @Test
    @DisplayName("透传源：device.alarm 帧命中 DEVICE_ALARM 规则新发告警")
    void deviceAlarmFrameMatchesPassthroughRule() {
        IotAlarmRuleEntity passthrough = rule(777L, AlarmRuleType.DEVICE_ALARM, AlarmLevel.WARNING);
        passthrough.setMetricCode("deviceAlarmEvent");
        when(ruleMapper.selectList(any())).thenReturn(List.of(passthrough));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(bindingService.findActiveByDevice(DEVICE_ID))
                .thenReturn(Optional.of(binding(5L, "20260901000001", 1001L)));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(1001L));
        when(seqGate.nextAlarmNo()).thenReturn("AL2026092600003");
        when(alarmMapper.insert(any(IotAlarmEntity.class))).thenReturn(1);

        engine.evaluateDeviceAlarm(new DeviceAlarmFrame(DEVICE_ID, "deviceAlarmEvent", "MAJOR", "设备自检告警", NOW));

        verify(alarmMapper).insert(alarmCaptor.capture());
        assertThat(alarmCaptor.getValue().getMetricCode()).isEqualTo("deviceAlarmEvent");
        assertThat(alarmCaptor.getValue().getTriggerValue()).isEqualTo("设备自检告警");
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(IotMessagingConstants.EVENT_ALARM_TRIGGERED);
        verify(pushService).pushAlarm(alarmCaptor.getValue());
    }

    @Test
    @DisplayName("透传源：未命中规则的设备告警帧仅 info 跳过（零落库零事件）")
    void unmatchedDeviceAlarmFrameIsIgnored() {
        // 无 DEVICE_ALARM 规则（selectList 缺省空清单）

        engine.evaluateDeviceAlarm(new DeviceAlarmFrame(DEVICE_ID, "unknownAlarm", "MAJOR", "未登记告警", NOW));

        verify(alarmMapper, never()).insert(any(IotAlarmEntity.class));
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("并发兜底：单条落行唯一冲突幂等跳过不污染批事务——同批其余规则×设备告警、离线评估与升级扫描照常")
    void duplicateKeyOnOneAlarmSkipsOnlyThatAlarmAndKeepsBatchEvaluating() {
        // 规则×设备独立命中面（ruleA 仅命中 dev-001 心率行、ruleB 仅命中 dev-002 血氧行）：
        // 首条 dev-001 落行并发命中 uk_iot_alarm_active，次条 dev-002 正常落行
        IotAlarmRuleEntity ruleB = rule(900002L, AlarmRuleType.THRESHOLD, AlarmLevel.WARNING);
        ruleB.setMetricCode("MDC_SPO2");
        ruleB.setCompareOp(ThresholdOp.LT);
        ruleB.setThresholdValue(new BigDecimal("90"));
        ruleB.setDurationSecs(30);
        ruleB.setRecoveryBand(new BigDecimal("5"));
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(), ruleB));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 两设备越限回合均已起算 60s（≥ 持续时长 30s）：双双触发
        when(valueOperations.get(anyString())).thenReturn(NOW.minusSeconds(60).toString());
        when(deviceMapper.selectList(any()))
                .thenReturn(List.of(device(1001L), device("dev-002", 1001L)));
        when(seqGate.nextAlarmNo()).thenReturn("AL2026092600001", "AL2026092600002", "AL2026092600003");
        // 首条落行并发唯一冲突（DB 兜底面），后续落行正常——模拟 PG 25P02 只应中止单条事务
        when(alarmMapper.insert(any(IotAlarmEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_iot_alarm_active 冲突"))
                .thenReturn(1);
        // 离线源：dev-003 候选（病区经设备档案兜底路由）
        IotAlarmRuleEntity offlineRule = rule(666L, AlarmRuleType.OFFLINE, AlarmLevel.WARNING);
        offlineRule.setOfflineSecs(600);
        IotDeviceEntity stalled = device("dev-003", 1002L);
        stalled.setLastOnlineAt(OffsetDateTime.ofInstant(Instant.now().minusSeconds(700), ZoneOffset.UTC));
        when(offlineDetector.detect()).thenReturn(List.of(new OfflineDetector.Candidate(offlineRule, stalled)));
        // 升级扫描：既有危急告警越升级时限（锚点相对真实时钟，规避墙钟偏差）
        IotAlarmEntity critical = new IotAlarmEntity();
        critical.setId(1L);
        critical.setAlarmNo("AL2026092500009");
        critical.setRuleId(RULE_ID);
        critical.setDeviceId(DEVICE_ID);
        critical.setWardId(1001L);
        critical.setAlarmLevel(AlarmLevel.CRITICAL);
        critical.setStatus(AlarmStatus.ACTIVE);
        critical.setEscalationCount(0);
        critical.setCreatedAt(OffsetDateTime.ofInstant(Instant.now().minusSeconds(600), ZoneOffset.UTC));
        when(alarmMapper.selectCriticalActive()).thenReturn(List.of(critical));
        when(ruleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(rule(), ruleB));
        when(alarmMapper.casEscalate(eq(1L), eq(0), anyString())).thenReturn(1);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(
                telemetryRow(DEVICE_ID, METRIC_CODE, "170", NOW),
                telemetryRow("dev-002", "MDC_SPO2", "88", NOW))));

        // 冲突单条（dev-001）仅消耗一次落行尝试；同批 dev-002 阈值告警与 dev-003 离线告警照常落行
        verify(alarmMapper, times(3)).insert(alarmCaptor.capture());
        List<IotAlarmEntity> insertedRows = alarmCaptor.getAllValues();
        assertThat(insertedRows).extracting(IotAlarmEntity::getDeviceId)
                .containsExactly(DEVICE_ID, "dev-002", "dev-003");
        assertThat(insertedRows.get(1).getAlarmNo()).isEqualTo("AL2026092600002");
        assertThat(insertedRows.get(1).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(insertedRows.get(1).getWardId()).isEqualTo(1001L);
        assertThat(insertedRows.get(2).getMetricCode()).isEqualTo("DEVICE_OFFLINE");
        assertThat(insertedRows.get(2).getWardId()).isEqualTo(1002L);
        // 冲突单条零事件：triggered 仅 dev-002/dev-003 两条 + 升级 escalated 一条（顺序同评估链）
        verify(events, times(3)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(IotDomainEvent::eventType)
                .containsExactly(
                        IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                        IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                        IotMessagingConstants.EVENT_ALARM_ESCALATED);
        assertThat(eventCaptor.getAllValues().stream()
                .map(IotDomainEvent::payload)
                .filter(AlarmTriggeredPayload.class::isInstance)
                .map(AlarmTriggeredPayload.class::cast))
                .extracting(AlarmTriggeredPayload::deviceId)
                .containsExactly("dev-002", "dev-003");
        verify(pushService, times(2)).pushAlarm(any(IotAlarmEntity.class));
        // 升级扫描不受冲突影响：CAS 升级照常执行
        verify(alarmMapper).casEscalate(eq(1L), eq(0), anyString());
    }

    @Test
    @DisplayName("最新值快照补写：数值行按 fy:iot:snapshot:latest:{deviceId}:{metricCode} 落值+时刻")
    void writesLatestValueSnapshot() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of(telemetryRow("170", NOW))));

        verify(valueOperations).set(eq(SNAPSHOT_KEY), eq("170|" + NOW.toEpochMilli()), eq(SNAPSHOT_TTL));
    }

    @Test
    @DisplayName("空批次零动作：不触库不触 Redis 不发布")
    void emptyBatchIsNoOp() {
        engine.evaluate(new AlarmEngine.TelemetryBatch(List.of()));

        verifyNoInteractions(ruleMapper, alarmMapper, seqGate, events, pushService, offlineDetector, redisTemplate);
    }

    /** 阈值规则桩（心率 >150 CRITICAL duration 30s recovery_band 10，V1008 种子同构） */
    private static IotAlarmRuleEntity rule() {
        IotAlarmRuleEntity entity = rule(RULE_ID, AlarmRuleType.THRESHOLD, AlarmLevel.CRITICAL);
        entity.setMetricCode(METRIC_CODE);
        entity.setCompareOp(ThresholdOp.GT);
        entity.setThresholdValue(new BigDecimal("150"));
        entity.setDurationSecs(30);
        entity.setRecoveryBand(new BigDecimal("10"));
        entity.setEscalateAfterSecs(300);
        return entity;
    }

    private static IotAlarmRuleEntity rule(long id, AlarmRuleType type, AlarmLevel level) {
        IotAlarmRuleEntity entity = new IotAlarmRuleEntity();
        entity.setId(id);
        entity.setRuleName("测试规则" + id);
        entity.setRuleType(type);
        entity.setAlarmLevel(level);
        entity.setEnabled(true);
        return entity;
    }

    private static IotTelemetryEntity telemetryRow(String value, Instant occurredAt) {
        return telemetryRow(DEVICE_ID, METRIC_CODE, value, occurredAt);
    }

    /** 指定设备+指标的遥测行桩（B1 并发冲突用例：双设备双指标独立命中面） */
    private static IotTelemetryEntity telemetryRow(
            String deviceId, String metricCode, String value, Instant occurredAt) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(deviceId);
        entity.setMetricCode(metricCode);
        entity.setValue(new BigDecimal(value));
        entity.setOccurredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return entity;
    }

    private static BindingVO binding(long patientId, String visitId, long wardId) {
        return new BindingVO(1L, DEVICE_ID, patientId, visitId, null, wardId, null, null, null, null, null, null, null);
    }

    private static IotDeviceEntity device(long wardId) {
        return device(DEVICE_ID, wardId);
    }

    /** 指定设备号的设备档案桩（B1 并发冲突用例：双设备病区兜底路由） */
    private static IotDeviceEntity device(String deviceId, long wardId) {
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(deviceId);
        entity.setWardId(wardId);
        entity.setStatus(DeviceStatus.ONLINE);
        return entity;
    }
}

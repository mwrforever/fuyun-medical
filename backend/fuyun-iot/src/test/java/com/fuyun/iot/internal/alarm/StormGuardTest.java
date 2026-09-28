package com.fuyun.iot.internal.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.internal.alarm.StormGuard.TriggerOutcome;
import com.fuyun.iot.mapper.IotAlarmMapper;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.properties.AlarmProperties;
import java.time.Duration;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 风暴抑制器单元测试（FU-M14-08 五项抑制之①③④判定与④风暴态/补推队列）：①同源聚合 CAS 命中、
 * ③离线抑制衍生遥测告警（非遥测路径豁免）、④触发计数超基线置风暴标记与解除后补推队列排空、
 * Redis 异常降级不上抛。真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class StormGuardTest {

    private static final long RULE_ID = 900001L;

    private static final String DEVICE_ID = "dev-001";

    private static final String STORM_KEY = "fy:iot:alarm:storm:" + RULE_ID;

    private static final String RATE_KEY = "fy:iot:alarm:rate:" + RULE_ID;

    private static final String PENDING_KEY = "fy:iot:alarm:pending:" + RULE_ID;

    private static final AlarmProperties PROPERTIES =
            new AlarmProperties(Duration.ofMinutes(10), 50, Duration.ofMinutes(10));

    @Mock
    private IotAlarmMapper alarmMapper;

    @Mock
    private IotAlarmRuleMapper ruleMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    private StormGuard stormGuard;

    @BeforeAll
    static void initTableInfo() {
        // 抑制③判定 selectBatchIds 不依赖 TableInfo，此处初始化为 lambda wrapper 兜底
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotAlarmEntity.class);
    }

    @BeforeEach
    void setUp() {
        stormGuard = new StormGuard(alarmMapper, ruleMapper, redisTemplate, PROPERTIES);
    }

    @Test
    @DisplayName("抑制判定放行：无活跃离线告警且 CAS 未命中活跃行 → Proceed 可新发")
    void proceedsWhenNoActiveAlarm() {
        when(alarmMapper.incrementTriggerIfActive(eq(RULE_ID), eq(DEVICE_ID), anyString()))
                .thenReturn(0);

        TriggerOutcome outcome = stormGuard.decide(RULE_ID, DEVICE_ID, "system", true);

        assertThat(outcome).isInstanceOf(TriggerOutcome.Proceed.class);
    }

    @Test
    @DisplayName("抑制①：CAS 命中同源活跃行 → Aggregated 聚合计数不新发")
    void aggregatesWhenCasHitsActiveRow() {
        when(alarmMapper.incrementTriggerIfActive(eq(RULE_ID), eq(DEVICE_ID), anyString()))
                .thenReturn(1);

        TriggerOutcome outcome = stormGuard.decide(RULE_ID, DEVICE_ID, "system", true);

        assertThat(outcome).isInstanceOf(TriggerOutcome.Aggregated.class);
    }

    @Test
    @DisplayName("抑制③：设备已有 ACTIVE 离线告警 → OfflineSuppressed（CAS 不再触达）")
    void suppressesDerivedAlarmWhenOfflineActiveExists() {
        IotAlarmEntity activeRow = new IotAlarmEntity();
        activeRow.setRuleId(555L);
        when(alarmMapper.selectActiveByDevice(DEVICE_ID)).thenReturn(List.of(activeRow));
        IotAlarmRuleEntity offlineRule = new IotAlarmRuleEntity();
        offlineRule.setId(555L);
        offlineRule.setRuleType(AlarmRuleType.OFFLINE);
        when(ruleMapper.selectBatchIds(anyCollection())).thenReturn(List.of(offlineRule));

        TriggerOutcome outcome = stormGuard.decide(RULE_ID, DEVICE_ID, "system", true);

        assertThat(outcome).isInstanceOf(TriggerOutcome.OfflineSuppressed.class);
        verify(alarmMapper, never()).incrementTriggerIfActive(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("抑制③豁免：离线规则源自身不受离线抑制（telemetryDerived=false 跳过③判定面）")
    void exemptsOfflineSourceFromDerivedSuppression() {
        when(alarmMapper.incrementTriggerIfActive(eq(RULE_ID), eq(DEVICE_ID), anyString()))
                .thenReturn(0);

        TriggerOutcome outcome = stormGuard.decide(RULE_ID, DEVICE_ID, "system", false);

        assertThat(outcome).isInstanceOf(TriggerOutcome.Proceed.class);
        // 豁免语义：抑制③的活跃离线告警判定面未被触达
        verify(alarmMapper, never()).selectActiveByDevice(anyString());
    }

    @Test
    @DisplayName("抑制④：触发计数超基线置风暴标记（TTL=风暴窗口）并返回风暴态")
    void entersStormWhenBurstExceedsBaseline() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(RATE_KEY)).thenReturn(51L);

        boolean storm = stormGuard.recordTriggerAndCheckStorm(RULE_ID);

        assertThat(storm).isTrue();
        verify(valueOperations).set(eq(STORM_KEY), eq("1"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("抑制④：计数未超基线不置风暴标记（沿用既有风暴态）")
    void keepsSilentWhenBurstBelowBaseline() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(RATE_KEY)).thenReturn(3L);
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);

        boolean storm = stormGuard.recordTriggerAndCheckStorm(RULE_ID);

        assertThat(storm).isFalse();
        verify(valueOperations, never()).set(eq(STORM_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("抑制④自愈：非首触发也重续计数键 TTL（窗口起点键不依赖首触发存活）")
    void renewsRateKeyTtlOnEveryIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 第 7 次触发（非首触发）：仍须续期，防首触发 expire 丢失后遗留永久键
        when(valueOperations.increment(RATE_KEY)).thenReturn(7L);
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);

        stormGuard.recordTriggerAndCheckStorm(RULE_ID);

        verify(redisTemplate).expire(RATE_KEY, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("抑制④自愈：expire 异常不破坏风暴判定，下次成功触发重武 TTL（不产生永久键）")
    void healsRateKeyTtlAfterRenewalFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 首次续期抛异常（降级吞并），第二次成功（自愈重武）
        when(valueOperations.increment(RATE_KEY)).thenReturn(51L, 52L);
        when(redisTemplate.expire(eq(RATE_KEY), any(Duration.class)))
                .thenThrow(new DuplicateKeyException("ttl lost"))
                .thenReturn(true);

        // 第一次：续期失败不中断——风暴判定照常完成（超基线置位返回 true）
        assertThat(stormGuard.recordTriggerAndCheckStorm(RULE_ID)).isTrue();
        verify(valueOperations).set(eq(STORM_KEY), eq("1"), eq(Duration.ofMinutes(10)));
        // 第二次：成功触发重武 TTL（遗留无 TTL 计数键自愈）
        stormGuard.recordTriggerAndCheckStorm(RULE_ID);

        verify(redisTemplate, times(2)).expire(RATE_KEY, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("抑制④：风暴期被抑制告警号入补推队列")
    void queuesDeferredAlarmNo() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        stormGuard.queueDeferredPush(RULE_ID, "AL2026092600001");

        verify(listOperations).rightPush(PENDING_KEY, "AL2026092600001");
        verify(redisTemplate).expire(eq(PENDING_KEY), any(Duration.class));
    }

    @Test
    @DisplayName("抑制④：风暴未解除不取补推队列（只入库不推持续生效）")
    void drainsNothingWhileStormActive() {
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(true);

        List<String> drained = stormGuard.drainDeferredIfStormCleared(RULE_ID);

        assertThat(drained).isEmpty();
        verify(redisTemplate, never()).opsForList();
    }

    @Test
    @DisplayName("抑制④：风暴解除后排空补推队列并删键（解除后补推）")
    void drainsAllAfterStormCleared() {
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(PENDING_KEY)).thenReturn(2L);
        when(listOperations.leftPop(PENDING_KEY, 2L)).thenReturn(List.of("AL1", "AL2"));

        List<String> drained = stormGuard.drainDeferredIfStormCleared(RULE_ID);

        assertThat(drained).containsExactly("AL1", "AL2");
        verify(redisTemplate).delete(PENDING_KEY);
    }

    @Test
    @DisplayName("Redis 异常降级：计数失败不置位不上抛（风暴抑制为辅助语义不阻断触发链）")
    void degradesGracefullyOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(RATE_KEY)).thenThrow(new DuplicateKeyException("redis down"));
        when(redisTemplate.hasKey(STORM_KEY)).thenReturn(false);

        boolean storm = stormGuard.recordTriggerAndCheckStorm(RULE_ID);

        assertThat(storm).isFalse();
    }
}

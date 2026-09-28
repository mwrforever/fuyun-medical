package com.fuyun.iot.internal.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * 离线探测器单元测试（FU-M14-08 离线规则源）：ONLINE 设备 last_online_at 距今超 offline_secs
 * 圈定断流候选（惰性扫描随 evaluate 调起）；查询条件（状态 ONLINE/超时比较/规则指定设备收窄/
 * 单规则候选上限）经 wrapper SQL 片段断言；候选与规则配对关系断言；无离线规则零候选零设备查询。
 * 真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class OfflineDetectorTest {

    private static final String DEVICE_ID = "dev-001";

    @Mock
    private IotAlarmRuleMapper ruleMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<IotDeviceEntity>> deviceQueryCaptor;

    private OfflineDetector detector;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotAlarmRuleEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        detector = new OfflineDetector(ruleMapper, deviceMapper);
    }

    @Test
    @DisplayName("断流候选圈定：查询条件含 ONLINE 状态与最后在线超时比较，候选与规则成对返回")
    void detectsStalledOnlineDevices() {
        IotAlarmRuleEntity rule = offlineRule(null, 600);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        IotDeviceEntity stalled = device(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(700));
        when(deviceMapper.selectList(any())).thenReturn(List.of(stalled));

        List<OfflineDetector.Candidate> candidates = detector.detect();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).rule().getId()).isEqualTo(666L);
        assertThat(candidates.get(0).device().getDeviceId()).isEqualTo(DEVICE_ID);
        // 超时过滤条件在位：状态 ONLINE + last_online_at 比较列（真实比较行为归 IT 回归）
        verify(deviceMapper).selectList(deviceQueryCaptor.capture());
        String sqlSegment = deviceQueryCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("status").contains("last_online_at");
    }

    @Test
    @DisplayName("规则指定设备号：查询条件收窄到该设备（wrapper 携带 device_id 条件）")
    void narrowsQueryToRuleScopedDevice() {
        IotAlarmRuleEntity rule = offlineRule("dev-002", 300);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        detector.detect();

        verify(deviceMapper).selectList(deviceQueryCaptor.capture());
        assertThat(deviceQueryCaptor.getValue().getSqlSegment()).contains("device_id");
    }

    @Test
    @DisplayName("无离线规则：零候选零设备查询")
    void returnsEmptyWhenNoOfflineRules() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        assertThat(detector.detect()).isEmpty();
        verifyNoInteractions(deviceMapper);
    }

    private static IotAlarmRuleEntity offlineRule(String deviceId, int offlineSecs) {
        IotAlarmRuleEntity entity = new IotAlarmRuleEntity();
        entity.setId(666L);
        entity.setRuleName("离线规则");
        entity.setRuleType(AlarmRuleType.OFFLINE);
        entity.setDeviceId(deviceId);
        entity.setOfflineSecs(offlineSecs);
        entity.setAlarmLevel(AlarmLevel.WARNING);
        entity.setEnabled(true);
        return entity;
    }

    private static IotDeviceEntity device(OffsetDateTime lastOnlineAt) {
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(DEVICE_ID);
        entity.setStatus(DeviceStatus.ONLINE);
        entity.setLastOnlineAt(lastOnlineAt);
        return entity;
    }
}

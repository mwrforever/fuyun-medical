package com.fuyun.iot.internal.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 离线探测器单元测试（FU-M14-08 离线规则源，W-60 收敛后形态）：单条 UNION ALL 动态扫描一次
 * 触达替代逐规则循环单查——扫描 SQL 构造（ONLINE 状态/超时窗口 make_interval/规则设备收窄/
 * 升序+LIMIT 上限/NULL 秒数归零）经 mapper @Select 注解原文断言；分组配对、组内升序、
 * 单规则上限截断、同设备多规则重复配对（与逐规则查询逐语义等价，D-21 行为保持锚）经
 * 行为断言；无离线规则零候选零设备查询。真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class OfflineDetectorTest {

    private static final String DEVICE_ID = "dev-001";

    @Mock
    private IotAlarmRuleMapper ruleMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

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
    @DisplayName("断流候选圈定：扫描 SQL 携带 ONLINE 状态与超时窗口构造，候选与规则成对返回")
    void detectsStalledOnlineDevices() {
        IotAlarmRuleEntity rule = offlineRule(666L, null, 600);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(deviceMapper.selectOfflineCandidates(anyList(), anyInt()))
                .thenReturn(List.of(candidateRow(
                        666L, DEVICE_ID, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(700))));

        List<OfflineDetector.Candidate> candidates = detector.detect();

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).rule().getId()).isEqualTo(666L);
        assertThat(candidates.get(0).device().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(candidates.get(0).device().getStatus()).isEqualTo(DeviceStatus.ONLINE);
        // 谓词根因锚（真实比较行为归 IT 回归）：状态 ONLINE + 超时窗口 + 逻辑删过滤
        String sql = scanSql();
        assertThat(sql)
                .contains("d.status = 'ONLINE'")
                .contains("d.last_online_at")
                .contains("now() - make_interval")
                .contains("d.deleted = 0");
    }

    @Test
    @DisplayName("扫描 SQL 构造锚：规则窗口按 offline_secs 换算（COALESCE 归零）、规则指定设备收窄、升序+单规则上限")
    void scanSqlCarriesWindowDeviceScopeAndLimit() {
        assertThat(scanSql())
                .as("offline_secs NULL 归零与原逐规则口径一致；设备收窄条件分支；断流最久优先 + LIMIT 硬顶")
                .contains("COALESCE(#{r.offlineSecs}, 0)")
                .contains("<if test='r.deviceId != null'> AND d.device_id = #{r.deviceId}</if>")
                .contains("ORDER BY d.last_online_at ASC")
                .contains("LIMIT #{scanLimit}")
                .contains("UNION ALL");
    }

    @Test
    @DisplayName("批量收敛锚：多规则一次扫描触达（替代逐规则循环单查，A.4.3-14），同设备命中多规则重复配对（等价语义）")
    void scansAllRulesInSingleQueryAndPairsDevicePerRule() {
        IotAlarmRuleEntity ruleA = offlineRule(666L, null, 600);
        IotAlarmRuleEntity ruleB = offlineRule(777L, null, 300);
        when(ruleMapper.selectList(any())).thenReturn(List.of(ruleA, ruleB));
        OffsetDateTime stalled = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(700);
        // 同一断流设备在两规则分支各出现一行（rule_id 标记区分）——与逐规则两次查询各自配对同构
        when(deviceMapper.selectOfflineCandidates(anyList(), anyInt()))
                .thenReturn(List.of(
                        candidateRow(666L, DEVICE_ID, stalled),
                        candidateRow(777L, DEVICE_ID, stalled),
                        candidateRow(777L, "dev-002", stalled.plusSeconds(100))));

        List<OfflineDetector.Candidate> candidates = detector.detect();

        // 单次触达锚：全部规则经一条扫描 SQL 承载（禁循环内单查）
        verify(deviceMapper, times(1)).selectOfflineCandidates(anyList(), anyInt());
        // 配对等价锚：候选按（规则,设备）成对展开——规则装载序为组间序，组内按断流时长升序
        assertThat(candidates).hasSize(3);
        assertThat(candidates.get(0).rule().getId()).isEqualTo(666L);
        assertThat(candidates.get(0).device().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(candidates.get(1).rule().getId()).isEqualTo(777L);
        assertThat(candidates.get(1).device().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(candidates.get(2).rule().getId()).isEqualTo(777L);
        assertThat(candidates.get(2).device().getDeviceId()).isEqualTo("dev-002");
    }

    @Test
    @DisplayName("组内升序与截断：分支行按 last_online_at 升序排序后裁至单规则上限（断流最久者优先处置）")
    void ordersGroupByLastOnlineAtAndCapsAtScanLimit() {
        IotAlarmRuleEntity rule = offlineRule(666L, null, 600);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        // 构造超上限分支行（乱序回放模拟 UNION 输出序无契约）：最久断流设备须排首且上限外行被裁
        List<IotDeviceMapper.OfflineCandidateRow> rows = new ArrayList<>();
        IntStream.rangeClosed(1, OfflineDetector.SCAN_LIMIT + 5)
                .forEach(i -> rows.add(candidateRow(
                        666L,
                        "dev-%03d".formatted(i),
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(i * 10L))));
        when(deviceMapper.selectOfflineCandidates(anyList(), anyInt())).thenReturn(rows);

        List<OfflineDetector.Candidate> candidates = detector.detect();

        // 上限硬顶：候选数恰为 SCAN_LIMIT（越界行泄压裁除，防告警风暴）
        assertThat(candidates).hasSize(OfflineDetector.SCAN_LIMIT);
        // 升序锚：断流最久（last_online_at 最小）的 dev-205（minusSeconds 2050）排首——
        // 最短断流的 dev-001~dev-005 落在上限外被裁
        assertThat(candidates.get(0).device().getDeviceId()).isEqualTo("dev-205");
        assertThat(candidates).extracting(c -> c.device().getDeviceId()).doesNotContain("dev-001");
        // 升序贯穿断言：候选序列按 last_online_at 单调不减
        assertThat(candidates).isSortedAccordingTo(java.util.Comparator.comparing(c -> c.device()
                .getLastOnlineAt()));
    }

    @Test
    @DisplayName("无候选：规则在位但扫描零命中 → 空候选清单（单次触达）")
    void returnsEmptyWhenScanFindsNoCandidates() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(offlineRule(666L, null, 600)));
        when(deviceMapper.selectOfflineCandidates(anyList(), anyInt())).thenReturn(List.of());

        assertThat(detector.detect()).isEmpty();
        verify(deviceMapper, times(1)).selectOfflineCandidates(anyList(), anyInt());
    }

    @Test
    @DisplayName("无离线规则：零候选零设备查询（规则装载空集短路，禁空 foreach 生成非法 SQL）")
    void returnsEmptyWhenNoOfflineRules() {
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        assertThat(detector.detect()).isEmpty();
        verifyNoInteractions(deviceMapper);
    }

    /** 构造启用状态的 OFFLINE 规则（离线窗口秒数与指定设备收窄可变参）。 */
    private static IotAlarmRuleEntity offlineRule(long id, String deviceId, int offlineSecs) {
        IotAlarmRuleEntity entity = new IotAlarmRuleEntity();
        entity.setId(id);
        entity.setRuleName("离线规则" + id);
        entity.setRuleType(AlarmRuleType.OFFLINE);
        entity.setDeviceId(deviceId);
        entity.setOfflineSecs(offlineSecs);
        entity.setAlarmLevel(AlarmLevel.WARNING);
        entity.setEnabled(true);
        return entity;
    }

    /** 构造扫描投影行（rule_id 分支标记 + 断流设备档案快照）。 */
    private static IotDeviceMapper.OfflineCandidateRow candidateRow(
            long ruleId, String deviceId, OffsetDateTime lastOnlineAt) {
        IotDeviceMapper.OfflineCandidateRow row = new IotDeviceMapper.OfflineCandidateRow();
        row.setRuleId(ruleId);
        row.setDeviceId(deviceId);
        row.setStatus(DeviceStatus.ONLINE);
        row.setLastOnlineAt(lastOnlineAt);
        return row;
    }

    /**
     * 直读扫描方法 @Select 注解 SQL 原文（谓词根因锚：单条动态扫描构造必须为注解 SQL 承载）。
     *
     * @return 拼接后的注解 SQL 全文
     */
    private String scanSql() {
        try {
            Method method = IotDeviceMapper.class.getMethod("selectOfflineCandidates", List.class, int.class);
            Select select = method.getAnnotation(Select.class);
            assertThat(select).as("离线扫描必须为 @Select 注解 SQL（W-60 收敛形态）").isNotNull();
            return String.join("", select.value());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("扫描方法不存在：selectOfflineCandidates", e);
        }
    }
}

package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.impl.DeviceStatusServiceImpl;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 设备状态服务单元测试（BRIEF-PR4-01 §3 单测清单：存在更新/不存在跳过/在线离线时间字段分派；
 * P2 PR-2 Task 5 扩 Redis 快照面：fy:iot:snapshot:device-status:{deviceId} 五态+lastOnlineAt、
 * TTL 24h、String JSON 承载、写失败降级不阻状态机）。JaCoCo 核心包
 * com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>时间字段分派语义（14-iot §5）经 lambdaUpdate 的 sqlSet 投影断言：ONLINE 只写 last_online_at、
 * OFFLINE 只写 last_offline_at、其余状态不写任何时间字段；真实 SQL 行为归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
class DeviceStatusServiceImplTest {

    private static final String DEVICE_ID = "it-dev-001";

    /** 快照键形态（GC13 A.5-1 命名：fy:iot:snapshot:device-status:{deviceId}） */
    private static final String SNAPSHOT_KEY = "fy:iot:snapshot:device-status:" + DEVICE_ID;

    /** 快照 TTL 契约：24 小时（≥2×采集周期，禁无 TTL 键红线） */
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(24);

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-10T05:30:00Z");

    /** 测试病区 ID：档案种子病区，apply 返回值（事件补全 wardId）断言值 */
    private static final long WARD_ID = 1001L;

    /** 档案既有最近上线时刻（非 ONLINE 状态快照应保留的历史值） */
    private static final OffsetDateTime PRIOR_ONLINE_AT = OffsetDateTime.parse("2026-09-09T01:00:00Z");

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<IotDeviceEntity>> updateCaptor;

    @Captor
    private ArgumentCaptor<String> snapshotJsonCaptor;

    private DeviceStatusServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 状态机更新 lambda 条件/投影的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeviceStatusServiceImpl(deviceMapper, redisTemplate, objectMapper());
    }

    @Test
    @DisplayName("设备存在且状态为 ONLINE：更新 status 并分派写 last_online_at（不写 last_offline_at），返回档案 wardId")
    void applyOnlineStatusDispatchesLastOnlineAtOnly() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.OFFLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Long appliedWardId = service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.ONLINE, OCCURRED_AT, null));

        assertThat(appliedWardId).as("有效设备返回档案 wardId（消费侧事件补全依据）").isEqualTo(WARD_ID);
        String sqlSet = capturedSqlSet();
        assertThat(sqlSet).contains("status").contains("last_online_at").doesNotContain("last_offline_at");
    }

    @Test
    @DisplayName("设备存在且状态为 OFFLINE：更新 status 并分派写 last_offline_at（不写 last_online_at），返回档案 wardId")
    void applyOfflineStatusDispatchesLastOfflineAtOnly() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.ONLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Long appliedWardId = service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.OFFLINE, OCCURRED_AT, null));

        assertThat(appliedWardId).as("有效设备返回档案 wardId（消费侧事件补全依据）").isEqualTo(WARD_ID);
        String sqlSet = capturedSqlSet();
        assertThat(sqlSet).contains("status").contains("last_offline_at").doesNotContain("last_online_at");
    }

    @Test
    @DisplayName("其余状态（ABNORMAL 等）：仅更新 status，任何时间字段都不动")
    void applyNonOnlineOfflineStatusSkipsTimeFields() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.ONLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Long appliedWardId = service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.ABNORMAL, OCCURRED_AT, null));

        assertThat(appliedWardId).isEqualTo(WARD_ID);
        String sqlSet = capturedSqlSet();
        assertThat(sqlSet).contains("status").doesNotContain("last_online_at").doesNotContain("last_offline_at");
    }

    @Test
    @DisplayName("设备档案不存在：info 跳过不更新并返回 null（调用方不发状态事件）")
    void applySkipsUnknownDeviceWithoutUpdate() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        Long appliedWardId =
                service.apply(new DeviceStatusEvent("ghost-device", DeviceStatus.ONLINE, OCCURRED_AT, null));

        assertThat(appliedWardId).as("无效设备返回 null（消费侧不发布事件）").isNull();
        verify(deviceMapper).selectOne(any());
        verifyNoMoreInteractions(deviceMapper);
    }

    @Test
    @DisplayName("条件更新未命中（并发竞态影响行数 0）：返回 null 不发布事件")
    void applyReturnsNullWhenConditionalUpdateMisses() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.ONLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(0);

        assertThatCode(() -> {
                    Long appliedWardId =
                            service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.ONLINE, OCCURRED_AT, null));
                    assertThat(appliedWardId).isNull();
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("状态快照写入：状态机更新成功后落 fy:iot:snapshot:device-status:{deviceId}，ONLINE 五态+新 lastOnlineAt，TTL 24h")
    void applyOnlineWritesStatusSnapshotWithTwentyFourHourTtl() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.OFFLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.ONLINE, OCCURRED_AT, null));

        // String JSON 承载（禁 JDK 序列化红线）+ 显式 TTL（禁无 TTL 键红线）
        verify(valueOperations).set(eq(SNAPSHOT_KEY), snapshotJsonCaptor.capture(), eq(SNAPSHOT_TTL));
        String snapshotJson = snapshotJsonCaptor.getValue();
        assertThat(snapshotJson)
                .as("快照承载设备标识与五态 code（@JsonValue 序列化）")
                .contains(DEVICE_ID)
                .contains("\"status\":\"ONLINE\"");
        assertThat(snapshotJson)
                .as("ONLINE 快照 lastOnlineAt 取状态发生时刻（与 last_online_at 列同源）")
                .contains("2026-09-10T05:30:00Z");
    }

    @Test
    @DisplayName("非 ONLINE 状态快照：五态承载新状态且保留档案既有 lastOnlineAt 不丢历史")
    void applyOfflineSnapshotKeepsPriorLastOnlineAt() {
        IotDeviceEntity device = existingDevice(DeviceStatus.ONLINE);
        device.setLastOnlineAt(PRIOR_ONLINE_AT);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.OFFLINE, OCCURRED_AT, null));

        verify(valueOperations).set(eq(SNAPSHOT_KEY), snapshotJsonCaptor.capture(), eq(SNAPSHOT_TTL));
        assertThat(snapshotJsonCaptor.getValue())
                .as("OFFLINE 快照状态置位且 lastOnlineAt 保留档案历史值")
                .contains("\"status\":\"OFFLINE\"")
                .contains("2026-09-09T01:00:00Z");
    }

    @Test
    @DisplayName("快照写失败降级：Redis 异常仅 warn 不阻断状态机（apply 正常返回 wardId）")
    void snapshotWriteFailureDegradesWithoutBreakingStateMachine() {
        when(deviceMapper.selectOne(any())).thenReturn(existingDevice(DeviceStatus.OFFLINE));
        when(deviceMapper.update(isNull(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("redis down"))
                .when(valueOperations)
                .set(any(String.class), any(String.class), any(Duration.class));

        Long appliedWardId = service.apply(new DeviceStatusEvent(DEVICE_ID, DeviceStatus.ONLINE, OCCURRED_AT, null));

        assertThat(appliedWardId).as("缓存降级不影响状态机主链").isEqualTo(WARD_ID);
    }

    @Test
    @DisplayName("无效设备不写快照：档案不存在时 Redis 零交互")
    void unknownDeviceWritesNoSnapshot() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        service.apply(new DeviceStatusEvent("ghost-device", DeviceStatus.ONLINE, OCCURRED_AT, null));

        verifyNoInteractions(redisTemplate);
    }

    /** 取捕获到的 lambdaUpdate 投影串（sqlSet 形如 status=#{...},last_online_at=#{...}） */
    private String capturedSqlSet() {
        verify(deviceMapper).update(isNull(), updateCaptor.capture());
        return updateCaptor.getValue().getSqlSet();
    }

    /** 构造档案存在性查询命中行（device_id/status/ward_id/last_online_at 四列投影；夹具仅置断言所需三列） */
    private static IotDeviceEntity existingDevice(DeviceStatus currentStatus) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setStatus(currentStatus);
        device.setWardId(WARD_ID);
        return device;
    }

    /** 测试用 JSON 转换器：注册时间模块（OffsetDateTime ISO 串形态对齐 Boot 容器默认） */
    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

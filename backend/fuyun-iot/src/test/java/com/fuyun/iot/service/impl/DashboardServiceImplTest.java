package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.iot.entity.IotConsumerStatEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.iot.vo.DashboardSummaryVO;
import com.fuyun.iot.vo.WardDeviceWallVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 运营大屏数据面服务单测（P2 PR-2 Task 11 Step 2，TDD 先行）：summary 六项聚合来源逐项断言、
 * Redis 缓存命中/回写 TTL 5s/损坏与降级三分支、refreshAndPushIfChanged 变更触发推送三分支、
 * 病区床位设备状态墙三面拼装（绑定五元组/设备状态快照回退/最新值快照解析）。JaCoCo 核心包
 * com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>mapper/绑定服务/推送服务/Redis 以 Mockito 模拟（真实 SQL 与快照行为归 fuyun-app 集成面）；
 * lambda 条件列名解析依赖 TableInfo（容器外单测手动初始化一次）。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    /** 大屏快照键（brief 冻结：fy:iot:snapshot:dashboard） */
    private static final String DASHBOARD_KEY = "fy:iot:snapshot:dashboard";

    /** 大屏快照 TTL 契约：5 秒（brief 冻结） */
    private static final Duration DASHBOARD_TTL = Duration.ofSeconds(5);

    /** 测试病区 ID */
    private static final long WARD_ID = 1001L;

    /** 测试设备号夹具 */
    private static final String DEVICE_ID = "dev-001";

    @Mock
    private com.fuyun.iot.mapper.IotDeviceMapper deviceMapper;

    @Mock
    private com.fuyun.iot.mapper.IotAlarmMapper alarmMapper;

    @Mock
    private com.fuyun.iot.mapper.IotConsumerStatMapper consumerStatMapper;

    @Mock
    private com.fuyun.iot.mapper.IotDataQualityStatMapper statMapper;

    @Mock
    private com.fuyun.iot.mapper.IotMetricMappingMapper metricMappingMapper;

    @Mock
    private IBindingService bindingService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ITelemetryPushService pushService;

    @Captor
    private ArgumentCaptor<String> cacheJsonCaptor;

    private DashboardServiceImpl service;

    private ObjectMapper objectMapper;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.fuyun.iot.entity.IotMetricMappingEntity.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new DashboardServiceImpl(
                deviceMapper,
                alarmMapper,
                consumerStatMapper,
                statMapper,
                metricMappingMapper,
                bindingService,
                redisTemplate,
                objectMapper,
                pushService);
    }

    // ---------------------------------------------------------------- summary 聚合六项

    @Test
    @DisplayName("summary 聚合六项来源逐项就位：在线/离线/总数/活跃告警/风暴态/积压水位/质量分")
    void summaryAggregatesSixSources() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        when(deviceMapper.selectCount(any())).thenReturn(3L, 1L, 10L);
        when(alarmMapper.selectCount(any())).thenReturn(2L);
        // 风暴键扫描命中 → stormActive=true
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(Boolean.TRUE);
        when(consumerStatMapper.selectLatestPerGroup(any())).thenReturn(List.of(consumerStat(new BigDecimal("0.25"))));
        when(statMapper.selectObjs(any())).thenReturn(List.of(new BigDecimal("97.50")));

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.deviceTotal()).isEqualTo(10);
        assertThat(vo.onlineCount()).isEqualTo(3);
        assertThat(vo.offlineCount()).isEqualTo(1);
        assertThat(vo.activeAlarmCount()).isEqualTo(2);
        assertThat(vo.stormActive()).isTrue();
        assertThat(vo.backlogEstimate()).isEqualByComparingTo("0.25");
        assertThat(vo.qualityScore()).isEqualByComparingTo("97.50");
    }

    @Test
    @DisplayName("summary 缓存命中：读 fy:iot:snapshot:dashboard 直返，不触任何聚合源")
    void summaryReturnsCachedSnapshotWithoutSourceQueries() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DashboardSummaryVO cached =
                new DashboardSummaryVO(10, 3, 1, 2, true, new BigDecimal("0.25"), new BigDecimal("97.50"));
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(toJson(cached));

        DashboardSummaryVO vo = service.summary();

        assertThat(vo).isEqualTo(cached);
        verifyNoInteractions(deviceMapper, alarmMapper, consumerStatMapper, statMapper, pushService);
    }

    @Test
    @DisplayName("summary 缓存缺席：直算后回写快照（TTL 5s、String JSON 承载）")
    void summaryComputesAndWritesCacheWithTtlOnMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        when(deviceMapper.selectCount(any())).thenReturn(3L, 1L, 10L);
        when(alarmMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(Boolean.FALSE);
        when(consumerStatMapper.selectLatestPerGroup(any())).thenReturn(List.of());
        when(statMapper.selectObjs(any())).thenReturn(List.of());

        service.summary();

        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_KEY),
                        cacheJsonCaptor.capture(),
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_TTL));
        DashboardSummaryVO written = fromJson(cacheJsonCaptor.getValue());
        assertThat(written.deviceTotal()).isEqualTo(10);
        assertThat(written.stormActive()).isFalse();
        assertThat(written.backlogEstimate()).as("无积压快照为 null").isNull();
        assertThat(written.qualityScore()).as("当日无统计行为 null").isNull();
    }

    @Test
    @DisplayName("summary 缓存损坏：JSON 解析失败降级直算（不抛错），重算结果回写覆盖")
    void summaryFallsBackToComputeWhenCacheCorrupted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn("{not-json");
        when(deviceMapper.selectCount(any())).thenReturn(1L, 0L, 1L);
        when(alarmMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(Boolean.FALSE);
        when(consumerStatMapper.selectLatestPerGroup(any())).thenReturn(List.of());
        when(statMapper.selectObjs(any())).thenReturn(List.of());

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.deviceTotal()).isEqualTo(1);
        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_KEY),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_TTL));
    }

    @Test
    @DisplayName("summary Redis 异常：读失败降级直算（缓存面缺席不阻断聚合主链）")
    void summaryDegradesToComputeWhenRedisReadFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenThrow(new IllegalStateException("Redis 不可达"));
        when(deviceMapper.selectCount(any())).thenReturn(1L, 0L, 1L);
        when(alarmMapper.selectCount(any())).thenReturn(0L);
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(Boolean.FALSE);
        when(consumerStatMapper.selectLatestPerGroup(any())).thenReturn(List.of());
        when(statMapper.selectObjs(any())).thenReturn(List.of());

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.deviceTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary Redis 异常：快照回写失败降级（不抛错，聚合结果照常返回）")
    void summaryDegradesQuietlyWhenCacheWriteFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        doThrow(new IllegalStateException("写超时"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));
        stubCompute(1, 0, 1, 0L, false, null, null);

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.deviceTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary 风暴键扫描 Redis 异常：降级为非风暴（横幅缺失不阻断大屏）")
    void summaryDegradesToNonStormWhenScanFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        when(redisTemplate.execute(anyRedisCallback())).thenThrow(new IllegalStateException("SCAN 失败"));
        stubComputeRest(1, 0, 1, 0L, null, null);

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.stormActive()).isFalse();
        assertThat(vo.deviceTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary 风暴键扫描真实游走：RedisCallback 执行 connection.scan 首个命中即判风暴")
    void summaryStormScanWalksCursorOverConnection() {
        org.springframework.data.redis.connection.RedisConnection connection =
                org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnection.class);
        org.springframework.data.redis.core.Cursor<byte[]> cursor =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.Cursor.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        // 回调真执行（非 mock 直返）：execute 透传到 RedisCallback.doInRedis，覆盖 SCAN 游走体
        when(redisTemplate.execute(anyRedisCallback()))
                .thenAnswer(invocation -> ((org.springframework.data.redis.core.RedisCallback<Boolean>)
                                invocation.getArgument(0))
                        .doInRedis(connection));
        when(connection.scan(any(org.springframework.data.redis.core.ScanOptions.class)))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true);
        stubComputeRest(1, 0, 1, 0L, null, null);

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.stormActive()).as("游走首个命中即风暴态").isTrue();
    }

    // ---------------------------------------------------------------- refreshAndPushIfChanged

    @Test
    @DisplayName("变更触发推送：缓存承载上次摘要与新算结果不同 → 推 /topic/iot/dashboard/global 并回写")
    void refreshPushesWhenSummaryChanged() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DashboardSummaryVO previous = new DashboardSummaryVO(10, 3, 1, 2, false, null, null);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(toJson(previous));
        stubCompute(3, 0, 10, 2L, false, null, null);

        service.refreshAndPushIfChanged();

        verify(pushService)
                .pushDashboardSummary(
                        org.mockito.ArgumentMatchers.argThat(s -> s.onlineCount() == 3 && s.offlineCount() == 0));
        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_KEY),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_TTL));
    }

    @Test
    @DisplayName("未变更不推送：新算摘要与缓存一致 → 仅回写缓存（续期），推送面零交互")
    void refreshSkipsPushWhenSummaryUnchanged() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DashboardSummaryVO same = new DashboardSummaryVO(10, 3, 0, 2, false, null, null);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(toJson(same));
        stubCompute(3, 0, 10, 2L, false, null, null);

        service.refreshAndPushIfChanged();

        verify(pushService, never()).pushDashboardSummary(any());
        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_KEY),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(DASHBOARD_TTL));
    }

    @Test
    @DisplayName("首次基准推送：缓存缺席（无上次摘要）→ 视为变更推送（自事件链路首帧基准）")
    void refreshPushesOnFirstBaselineWhenCacheAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        stubCompute(1, 0, 1, 0L, false, null, null);

        service.refreshAndPushIfChanged();

        verify(pushService).pushDashboardSummary(any());
    }

    // ---------------------------------------------------------------- 病区床位设备状态墙

    @Test
    @DisplayName("病区状态墙三面拼装：绑定五元组 + 设备状态（快照优先）+ 最新值（产品映射展开快照键）")
    void wardWallAssemblesBindingStatusAndLatestValues() {
        // 面①绑定五元组：一固定绑定（床位 2001）+ 一移动绑定（无床位）
        when(bindingService.listByWard(WARD_ID))
                .thenReturn(List.of(binding(DEVICE_ID, 2001L), binding("dev-002", null)));
        // 面②设备状态：档案行承载名称/回退状态/产品归属
        IotDeviceEntity device = device(DEVICE_ID, DeviceStatus.OFFLINE, "prod-ecg");
        IotDeviceEntity mobile = device("dev-002", DeviceStatus.ONLINE, null);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device, mobile));
        // 状态快照：dev-001 在挂（ONLINE 优先于档案 OFFLINE），dev-002 缺席（回退档案 ONLINE）
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(java.util.Arrays.asList(statusSnapshotJson(DEVICE_ID, DeviceStatus.ONLINE), null))
                .thenReturn(java.util.Arrays.asList("76|1758892800000", "98|1758892800001"));
        // 面③最新值：dev-001 产品映射两指标（心率/血氧），dev-002 无产品（无最新值）
        when(metricMappingMapper.selectList(any()))
                .thenReturn(
                        List.of(mapping("prod-ecg", "MDC_ECG_HEART_RATE"), mapping("prod-ecg", "MDC_PULSE_OXIM_SPO2")));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.wardId()).isEqualTo(WARD_ID);
        assertThat(wall.items()).hasSize(2);
        WardDeviceWallVO.BedDeviceItem first = wall.items().get(0);
        assertThat(first.bedId()).isEqualTo(2001);
        assertThat(first.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(first.deviceName()).isEqualTo("监护仪-01");
        assertThat(first.patientId()).isEqualTo(5L);
        assertThat(first.visitId()).isEqualTo("I2026090100001");
        assertThat(first.status()).as("状态快照优先于档案").isEqualTo(DeviceStatus.ONLINE);
        assertThat(first.latestValues()).hasSize(2);
        assertThat(first.latestValues().get(0).metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(first.latestValues().get(0).value()).isEqualByComparingTo("76");
        assertThat(first.latestValues().get(0).occurredAt())
                .isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochMilli(1758892800000L), ZoneOffset.UTC));
        WardDeviceWallVO.BedDeviceItem second = wall.items().get(1);
        assertThat(second.bedId()).as("移动式绑定无床位").isNull();
        assertThat(second.status()).as("状态快照缺席回退档案状态").isEqualTo(DeviceStatus.ONLINE);
        assertThat(second.latestValues()).as("无产品映射设备无最新值面").isEmpty();
    }

    @Test
    @DisplayName("病区无生效绑定：状态墙返回空条目清单，不触设备/映射/快照查询")
    void wardWallReturnsEmptyWhenNoActiveBindings() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of());

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wardWallEmpty(wall)).isTrue();
        verifyNoInteractions(deviceMapper, metricMappingMapper, redisTemplate);
    }

    @Test
    @DisplayName("状态快照批级读失败：整体降级回退档案状态（异常不阻断状态墙装配）")
    void wardWallFallsBackToDeviceStatusWhenSnapshotReadFails() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.OFFLINE, null)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection())).thenThrow(new IllegalStateException("MGET 失败"));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).status()).as("快照读失败回退档案状态").isEqualTo(DeviceStatus.OFFLINE);
        verify(metricMappingMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("状态快照批级读返回 null：全部条目回退档案状态（MGET 空响应防御）")
    void wardWallFallsBackToDeviceStatusWhenSnapshotReadReturnsNull() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ABNORMAL, null)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection())).thenReturn(null);

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).status()).isEqualTo(DeviceStatus.ABNORMAL);
    }

    @Test
    @DisplayName("全设备未挂产品：跳过映射与最新值查询，状态墙仅承载绑定与状态两面")
    void wardWallSkipsLatestValuesWhenNoDeviceHasProduct() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ONLINE, null)));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection())).thenReturn(java.util.Arrays.asList((String) null));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).latestValues()).isEmpty();
        verify(metricMappingMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("最新值快照损坏行跳过：缺席/空串/无分隔符/数值非法逐行降级，健康行照常解析")
    void wardWallSkipsMalformedLatestValuePayloads() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ONLINE, "prod-ecg")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(java.util.Arrays.asList((String) null))
                .thenReturn(java.util.Arrays.asList(null, "", "no-separator", "abc|notanumber", "76|1758892800000"));
        when(metricMappingMapper.selectList(any()))
                .thenReturn(List.of(
                        mapping("prod-ecg", "MDC_A"),
                        mapping("prod-ecg", "MDC_B"),
                        mapping("prod-ecg", "MDC_C"),
                        mapping("prod-ecg", "MDC_D"),
                        mapping("prod-ecg", "MDC_ECG_HEART_RATE")));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).latestValues()).hasSize(1);
        assertThat(wall.items().get(0).latestValues().get(0).metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(wall.items().get(0).latestValues().get(0).value()).isEqualByComparingTo("76");
    }

    @Test
    @DisplayName("最新值快照批级读失败：整体降级为最新值面缺席（辅助面不阻断状态墙主面）")
    void wardWallDegradesLatestValuesWhenSnapshotReadFails() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ONLINE, "prod-ecg")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(java.util.Arrays.asList((String) null))
                .thenThrow(new IllegalStateException("MGET 失败"));
        when(metricMappingMapper.selectList(any())).thenReturn(List.of(mapping("prod-ecg", "MDC_ECG_HEART_RATE")));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).latestValues()).isEmpty();
        assertThat(wall.items().get(0).status()).isEqualTo(DeviceStatus.ONLINE);
    }

    @Test
    @DisplayName("绑定引用设备档案缺失：条目以 null 档案面装配（名称/状态/最近上线为空），状态快照零查询")
    void wardWallHandlesMissingDeviceArchive() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding("dev-404", 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        WardDeviceWallVO.BedDeviceItem item = wall.items().get(0);
        assertThat(item.deviceId()).isEqualTo("dev-404");
        assertThat(item.deviceName()).as("档案缺失名称为空").isNull();
        assertThat(item.status()).as("档案缺失状态为空（无快照可回退）").isNull();
        assertThat(item.lastOnlineAt()).as("档案缺失最近上线为空").isNull();
        assertThat(item.latestValues()).isEmpty();
        verify(metricMappingMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("产品无任何映射指标：最新值快照键展开为空跳过 MGET，最新值面为空清单")
    void wardWallSkipsLatestKeysWhenProductHasNoMappings() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ONLINE, "prod-ecg")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection())).thenReturn(java.util.Arrays.asList((String) null));
        when(metricMappingMapper.selectList(any())).thenReturn(List.of());

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).latestValues()).isEmpty();
    }

    @Test
    @DisplayName("最新值快照 MGET 空响应：逐行防御跳过（payloads 为 null 不解析不抛错）")
    void wardWallHandlesNullLatestSnapshotResponse() {
        when(bindingService.listByWard(WARD_ID)).thenReturn(List.of(binding(DEVICE_ID, 2001L)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(DEVICE_ID, DeviceStatus.ONLINE, "prod-ecg")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyCollection()))
                .thenReturn(java.util.Arrays.asList((String) null))
                .thenReturn(null);
        when(metricMappingMapper.selectList(any())).thenReturn(List.of(mapping("prod-ecg", "MDC_ECG_HEART_RATE")));

        WardDeviceWallVO wall = service.wardWall(WARD_ID);

        assertThat(wall.items().get(0).latestValues()).isEmpty();
    }

    @Test
    @DisplayName("summary 风暴键扫描游走异常包装降级：SCAN 中途失败 → 横幅判 false 不抛错")
    void summaryStormScanDegradesWhenScanWalkFails() {
        org.springframework.data.redis.connection.RedisConnection connection =
                org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnection.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        when(redisTemplate.execute(anyRedisCallback()))
                .thenAnswer(invocation -> ((org.springframework.data.redis.core.RedisCallback<Boolean>)
                                invocation.getArgument(0))
                        .doInRedis(connection));
        when(connection.scan(any(org.springframework.data.redis.core.ScanOptions.class)))
                .thenThrow(new RuntimeException("SCAN 游走中断"));
        stubComputeRest(1, 0, 1, 0L, null, null);

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.stormActive()).isFalse();
    }

    @Test
    @DisplayName("summary 计数 null 防御：mapper 返回 null 归零（mock/降级场景不 NPE）")
    void summaryHandlesNullCountDefensively() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(DASHBOARD_KEY)).thenReturn(null);
        when(deviceMapper.selectCount(any())).thenReturn(null, null, null);
        when(alarmMapper.selectCount(any())).thenReturn(null);
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(Boolean.FALSE);
        when(consumerStatMapper.selectLatestPerGroup(any())).thenReturn(List.of());
        when(statMapper.selectObjs(any())).thenReturn(List.of());

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.deviceTotal()).isZero();
        assertThat(vo.onlineCount()).isZero();
        assertThat(vo.offlineCount()).isZero();
        assertThat(vo.activeAlarmCount()).isZero();
    }

    // ---------------------------------------------------------------- 夹具与工具

    private void stubCompute(
            long online,
            long offline,
            long total,
            long activeAlarms,
            boolean storm,
            BigDecimal backlog,
            BigDecimal quality) {
        when(deviceMapper.selectCount(any())).thenReturn(online, offline, total);
        when(alarmMapper.selectCount(any())).thenReturn(activeAlarms);
        when(redisTemplate.execute(anyRedisCallback())).thenReturn(storm);
        when(consumerStatMapper.selectLatestPerGroup(any()))
                .thenReturn(backlog == null ? List.of() : List.of(consumerStat(backlog)));
        when(statMapper.selectObjs(any())).thenReturn(quality == null ? List.of() : List.of(quality));
    }

    /** 聚合打桩（不含风暴扫描）：风暴扫描分支由各用例自行打桩（命中/异常/真实游走三分支） */
    private void stubComputeRest(
            long online, long offline, long total, long activeAlarms, BigDecimal backlog, BigDecimal quality) {
        when(deviceMapper.selectCount(any())).thenReturn(online, offline, total);
        when(alarmMapper.selectCount(any())).thenReturn(activeAlarms);
        when(consumerStatMapper.selectLatestPerGroup(any()))
                .thenReturn(backlog == null ? List.of() : List.of(consumerStat(backlog)));
        when(statMapper.selectObjs(any())).thenReturn(quality == null ? List.of() : List.of(quality));
    }

    private static IotConsumerStatEntity consumerStat(BigDecimal backlog) {
        IotConsumerStatEntity entity = new IotConsumerStatEntity();
        entity.setConsumerGroup("iot-amqp");
        entity.setSampledAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBacklogEstimate(backlog);
        return entity;
    }

    /** 生效绑定视图夹具（绑定快照五元组 + BOUND 终态，listByWard 契约返回 BindingVO） */
    private static BindingVO binding(String deviceId, Long bedId) {
        return new BindingVO(
                9001L,
                deviceId,
                5L,
                "I2026090100001",
                bedId,
                WARD_ID,
                BindType.FIXED,
                BindingStatus.BOUND,
                null,
                null,
                "E1001",
                null,
                null);
    }

    /** 设备档案夹具 */
    private static IotDeviceEntity device(String deviceId, DeviceStatus status, String productId) {
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(deviceId);
        entity.setDeviceName("监护仪-01");
        entity.setProductId(productId);
        entity.setStatus(status);
        return entity;
    }

    /** 物模型属性映射夹具（产品 → 指标编码） */
    private static IotMetricMappingEntity mapping(String productId, String metricCode) {
        IotMetricMappingEntity entity = new IotMetricMappingEntity();
        entity.setProductId(productId);
        entity.setMetricCode(metricCode);
        return entity;
    }

    /** 设备状态快照 JSON（DeviceStatusServiceImpl 快照契约同形：deviceId/status/lastOnlineAt） */
    private static String statusSnapshotJson(String deviceId, DeviceStatus status) {
        return "{\"deviceId\":\"" + deviceId + "\",\"status\":\"" + status.getCode()
                + "\",\"lastOnlineAt\":\"2026-09-26T08:00:00Z\"}";
    }

    private boolean wardWallEmpty(WardDeviceWallVO wall) {
        return wall.items().isEmpty();
    }

    /** 风暴键扫描回调桩：execute 多重载消歧（RedisCallback 形态与实现侧一致） */
    private static <T> org.springframework.data.redis.core.RedisCallback<T> anyRedisCallback() {
        return any();
    }

    private String toJson(DashboardSummaryVO vo) {
        try {
            return objectMapper.writeValueAsString(vo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DashboardSummaryVO fromJson(String json) {
        try {
            return objectMapper.readValue(json, DashboardSummaryVO.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

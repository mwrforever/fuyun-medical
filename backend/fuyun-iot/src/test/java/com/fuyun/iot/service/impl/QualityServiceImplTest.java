package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.QualityStatQueryRequest;
import com.fuyun.iot.entity.IotConsumerStatEntity;
import com.fuyun.iot.entity.IotDataQualityStatEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.IotAmqpMetrics;
import com.fuyun.iot.internal.IotDomainEvent;
import com.fuyun.iot.mapper.IotConsumerStatMapper;
import com.fuyun.iot.mapper.IotDataQualityStatMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.record.DailyQualityCountRow;
import com.fuyun.iot.record.DeviceMetricLastRow;
import com.fuyun.iot.vo.ConsumerStatVO;
import com.fuyun.iot.vo.DataQualityStatVO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 数据质量监控服务单元测试（FU-M14-11，brief Step 3 TDD）：统计推算公式（期望/缺数率/异常数/
 * 质量得分）、断流判定发布（超 3 倍标称周期无数据 → iot.telemetry.anomaly，去重门闸与 Redis
 * 降级）、消费积压快照采样（gauge 读取/未注册降级/NaN 跳过）。
 *
 * <p>测试数据全部合成（合成设备号/指标编码），与任何真实遥测无关；断流判定经发布器捕获
 * IotDomainEvent 断言载荷契约。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QualityServiceImplTest {

    private static final String DEVICE_ID = "dev-q01";
    private static final String METRIC = "MDC_TEST_HEART_RATE";
    private static final String PRODUCT_ID = "prod-01";

    @Mock
    private IotDataQualityStatMapper statMapper;

    @Mock
    private IotTelemetryMapper telemetryMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotMetricDictMapper metricDictMapper;

    @Mock
    private IotMetricMappingMapper metricMappingMapper;

    @Mock
    private IotConsumerStatMapper consumerStatMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<IotDataQualityStatEntity> statCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    private MeterRegistry meterRegistry;

    private QualityServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次，覆盖门槛链四实体）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, IotDataQualityStatEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, IotDeviceEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, IotMetricDictEntity.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, IotMetricMappingEntity.class);
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QualityServiceImpl(
                statMapper,
                telemetryMapper,
                deviceMapper,
                metricDictMapper,
                metricMappingMapper,
                consumerStatMapper,
                meterRegistry,
                redisTemplate,
                events);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient()
                .when(telemetryMapper.countDailyQuality(anyString(), any(), any()))
                .thenReturn(new DailyQualityCountRow(0L, 0L));
        lenient().when(statMapper.selectPage(any(), any())).thenReturn(pageOf(emptyStat()));
    }

    // ---------------------------------------------------------------- 统计推算公式

    @Test
    @DisplayName("统计公式：期望=标称频率×在线分钟数、缺数率/异常率/得分按口径现算并 UPSERT 落库")
    void qualityStatsRecomputesAndUpsertsDailyStat() {
        stubOnlineDeviceWithFreq("1");
        // 当日接收 720 行、异常 72 行：期望 1440 → 缺数率 0.5、异常率 0.1 → 得分 100×0.5×0.9=45.00
        when(telemetryMapper.countDailyQuality(eq(DEVICE_ID), any(), any()))
                .thenReturn(new DailyQualityCountRow(720L, 72L));

        service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        verify(statMapper).upsertStat(statCaptor.capture());
        IotDataQualityStatEntity stat = statCaptor.getValue();
        assertThat(stat.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(stat.getExpectedCount()).isEqualTo(1440L);
        assertThat(stat.getReceivedCount()).isEqualTo(720L);
        assertThat(stat.getMissingRate()).isEqualByComparingTo("0.5000");
        assertThat(stat.getAnomalyCount()).isEqualTo(72L);
        assertThat(stat.getQualityScore()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("未登记标称频率：期望与缺数率恒 0（防误报口径），得分按异常率折算")
    void qualityStatsWithoutNominalFreqKeepsZeroExpectedAndMissing() {
        stubOnlineDeviceWithFreq(null);

        service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        verify(statMapper).upsertStat(statCaptor.capture());
        assertThat(statCaptor.getValue().getExpectedCount()).isZero();
        assertThat(statCaptor.getValue().getMissingRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("离线设备：在线分钟数记 0 → 期望为 0、缺数率恒 0（在线时长近似口径）")
    void offlineDeviceCountsZeroOnlineMinutes() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        device.setStatus(DeviceStatus.OFFLINE);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        stubProductFreq("2");

        service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        verify(statMapper).upsertStat(statCaptor.capture());
        assertThat(statCaptor.getValue().getExpectedCount()).isZero();
    }

    @Test
    @DisplayName("显式指定设备不存在：IOT-1006 404，不落统计行")
    void qualityStatsUnknownDeviceRejectedWithIot1006() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null)))
                .isInstanceOfSatisfying(
                        BizException.class, e -> assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.DEVICE_NOT_FOUND));
        verify(statMapper, never()).upsertStat(any());
    }

    @Test
    @DisplayName("device-usage 出参折算：利用率=接收/期望（上限 1），期望为 0 记 0")
    void deviceUsageDerivesUsageRateFromStatRow() {
        stubOnlineDeviceWithFreq(null);
        IotDataQualityStatEntity row = new IotDataQualityStatEntity();
        row.setDeviceId(DEVICE_ID);
        row.setStatDate(java.time.LocalDate.of(2026, 9, 26));
        row.setExpectedCount(1440L);
        row.setReceivedCount(2880L);
        row.setMissingRate(new BigDecimal("0.0000"));
        row.setAnomalyCount(0L);
        row.setQualityScore(new BigDecimal("100.00"));
        when(statMapper.selectPage(any(), any())).thenReturn(pageOf(row));

        var result = service.deviceUsage(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        DataQualityStatVO vo = result.content().get(0);
        assertThat(vo.usageRate()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("未挂产品设备：标称频率无从定位，期望与缺数率恒 0 仍正常落统计行")
    void deviceWithoutProductKeepsZeroExpected() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId(null);
        device.setStatus(DeviceStatus.ONLINE);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));

        service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        verify(statMapper).upsertStat(statCaptor.capture());
        assertThat(statCaptor.getValue().getExpectedCount()).isZero();
    }

    @Test
    @DisplayName("产品无已映射指标：标称频率无从定位，期望与缺数率恒 0 仍正常落统计行")
    void productWithoutMappingsKeepsZeroExpected() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        device.setStatus(DeviceStatus.ONLINE);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(metricMappingMapper.selectList(any())).thenReturn(List.of());

        service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        verify(statMapper).upsertStat(statCaptor.capture());
        assertThat(statCaptor.getValue().getExpectedCount()).isZero();
    }

    // ---------------------------------------------------------------- 断流判定与发布

    @Test
    @DisplayName("断流判定：在线设备超 3 倍标称周期无数据 → 发布 STREAM_GAP 异常事件（载荷契约完整）")
    void staleTelemetryPublishesStreamGapAnomaly() {
        stubOnlineDeviceWithFreq("1");
        // 末次采集在 5 分钟前：阈值 3×60s=180s < gap 300s → 断流
        OffsetDateTime lastOccurred = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(300);
        stubLastOccurred(lastOccurred);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);

        int published = service.detectTelemetryAnomalies();

        assertThat(published).isEqualTo(1);
        verify(events).publishEvent(eventCaptor.capture());
        IotDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(IotMessagingConstants.EVENT_TELEMETRY_ANOMALY);
        com.fuyun.iot.api.payload.TelemetryAnomalyPayload payload =
                (com.fuyun.iot.api.payload.TelemetryAnomalyPayload) event.payload();
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.metricCode()).isEqualTo(METRIC);
        assertThat(payload.anomalyType()).isEqualTo(IotMessagingConstants.ANOMALY_TYPE_STREAM_GAP);
        assertThat(payload.lastOccurredAt().toEpochMilli())
                .isEqualTo(lastOccurred.toInstant().toEpochMilli());
        assertThat(payload.detectedAt()).isNotNull();
    }

    @Test
    @DisplayName("断流去重门闸：1 小时窗口内重复判定不再发布（SET NX 拦截）")
    void staleTelemetryWithinDedupWindowIsSuppressed() {
        stubOnlineDeviceWithFreq("1");
        stubLastOccurred(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(300));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(false);

        int published = service.detectTelemetryAnomalies();

        assertThat(published).isZero();
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("断流去重门闸 Redis 失败：本轮跳过发布（防查询频度事件风暴的降级口径）")
    void dedupGateFailureSkipsPublishing() {
        stubOnlineDeviceWithFreq("1");
        stubLastOccurred(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(300));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        int published = service.detectTelemetryAnomalies();

        assertThat(published).isZero();
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("采集新鲜（阈值内）与低频指标换算：不发布且标称周期按进位换算（0.5 次/分→120s）")
    void freshTelemetryAndLowFreqIntervalConversionSkipPublishing() {
        stubOnlineDeviceWithFreq("0.5");
        // 末次采集 200s 前：阈值 3×120s=360s > 200s → 未断流不发布
        stubLastOccurred(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(200));

        int published = service.detectTelemetryAnomalies();

        assertThat(published).isZero();
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("无在线设备与无采集历史：不发布（候选空集 / 字典指标从未上报无从判定）")
    void noOnlineDevicesOrNoTelemetryHistorySkipDetection() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.detectTelemetryAnomalies()).isZero();

        stubOnlineDeviceWithFreq("1");
        // 在册但无任何采集行：末次采集映射为空
        when(telemetryMapper.selectLastOccurredByDeviceMetric(any())).thenReturn(List.of());
        assertThat(service.detectTelemetryAnomalies()).isZero();
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("统计查询惰性调起断流判定失败：仅吞并不阻断统计主链路")
    void anomalyDetectionFailureDoesNotBlockStatsQuery() {
        stubOnlineDeviceWithFreq("1");
        // 断流判定内部链路故障（末次采集查询抛错），统计查询仍正常返回
        when(telemetryMapper.selectLastOccurredByDeviceMetric(any())).thenThrow(new IllegalStateException("db glitch"));
        when(statMapper.selectPage(any(), any())).thenReturn(pageOf(emptyStat()));

        var result = service.qualityStats(new QualityStatQueryRequest(0, 20, DEVICE_ID, null));

        assertThat(result.content()).hasSize(1);
        verify(events, never()).publishEvent(any(IotDomainEvent.class));
    }

    // ---------------------------------------------------------------- 消费积压快照

    @Test
    @DisplayName("断流候选截断留痕：在线设备触达扫描上限 200 行时 warn 泄压继续判定")
    void anomalyScanTruncationAtLimitLogsAndContinues() {
        // 合成 200 台在线设备（上限泄压分支），末次采集超时仅对首台设备成立
        java.util.List<IotDeviceEntity> devices = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            IotDeviceEntity device = new IotDeviceEntity();
            device.setDeviceId("dev-scan-" + i);
            device.setProductId(PRODUCT_ID);
            device.setStatus(DeviceStatus.ONLINE);
            devices.add(device);
        }
        when(deviceMapper.selectList(any())).thenReturn(devices);
        IotMetricMappingEntity mapping = new IotMetricMappingEntity();
        mapping.setProductId(PRODUCT_ID);
        mapping.setPropertyName("heartRate");
        mapping.setMetricCode(METRIC);
        when(metricMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        IotMetricDictEntity dict = new IotMetricDictEntity();
        dict.setMetricCode(METRIC);
        dict.setNominalFreqPerMin(new BigDecimal("1"));
        when(metricDictMapper.selectList(any())).thenReturn(List.of(dict));
        when(telemetryMapper.selectLastOccurredByDeviceMetric(any()))
                .thenReturn(java.util.List.of(new DeviceMetricLastRow(
                        "dev-scan-0", METRIC, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(300))));
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);

        int published = service.detectTelemetryAnomalies();

        assertThat(published).isEqualTo(1);
        verify(events).publishEvent(any(IotDomainEvent.class));
    }

    @Test
    @DisplayName("积压采样：gauge 注册时读填充率落快照行并返回每组最新快照")
    void consumerLagSamplesFillRatioWhenGaugeRegistered() {
        meterRegistry.gauge(IotAmqpMetrics.GAUGE_QUEUE_FILL_RATIO, new Object(), target -> 0.25);
        when(consumerStatMapper.selectLatestPerGroup(null)).thenReturn(List.of(consumerRow()));

        List<ConsumerStatVO> result = service.refreshAndListConsumerLag();

        verify(consumerStatMapper)
                .insert(argThat((IotConsumerStatEntity entity) ->
                        entity.getConsumerGroup().equals(IotMessagingConstants.LOCAL_CONSUMER_GROUP)
                                && entity.getBacklogEstimate().compareTo(new BigDecimal("0.2500")) == 0
                                && entity.getSampledAt() != null));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).backlogEstimate()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("积压采样降级：gauge 未注册（消费链未启用）跳过采样仅返回既有快照")
    void consumerLagSkipsSamplingWhenGaugeAbsent() {
        when(consumerStatMapper.selectLatestPerGroup(null)).thenReturn(List.of());

        List<ConsumerStatVO> result = service.refreshAndListConsumerLag();

        verify(consumerStatMapper, never()).insert(any(IotConsumerStatEntity.class));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("积压采样降级：gauge 读数 NaN（载体缺席）跳过落行不报错")
    void consumerLagSkipsSamplingWhenGaugeValueIsNaN() {
        meterRegistry.gauge(IotAmqpMetrics.GAUGE_QUEUE_FILL_RATIO, new Object(), target -> Double.NaN);

        service.refreshAndListConsumerLag();

        verify(consumerStatMapper, never()).insert(any(IotConsumerStatEntity.class));
    }

    // ---------------------------------------------------------------- 夹具

    private void stubOnlineDeviceWithFreq(String freq) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        device.setStatus(DeviceStatus.ONLINE);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        // 断流候选扫描与统计重算共用设备档案 mock（selectList 返回该在线设备）
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        stubProductFreq(freq);
    }

    private void stubProductFreq(String freq) {
        IotMetricMappingEntity mapping = new IotMetricMappingEntity();
        mapping.setProductId(PRODUCT_ID);
        mapping.setPropertyName("heartRate");
        mapping.setMetricCode(METRIC);
        when(metricMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        if (freq == null) {
            when(metricDictMapper.selectList(any())).thenReturn(List.of());
        } else {
            IotMetricDictEntity dict = new IotMetricDictEntity();
            dict.setMetricCode(METRIC);
            dict.setNominalFreqPerMin(new BigDecimal(freq));
            when(metricDictMapper.selectList(any())).thenReturn(List.of(dict));
        }
    }

    private void stubLastOccurred(OffsetDateTime lastOccurred) {
        when(telemetryMapper.selectLastOccurredByDeviceMetric(any()))
                .thenReturn(List.of(new DeviceMetricLastRow(DEVICE_ID, METRIC, lastOccurred)));
    }

    private static IotDataQualityStatEntity emptyStat() {
        IotDataQualityStatEntity row = new IotDataQualityStatEntity();
        row.setDeviceId(DEVICE_ID);
        row.setStatDate(java.time.LocalDate.of(2026, 9, 26));
        row.setExpectedCount(0L);
        row.setReceivedCount(0L);
        row.setMissingRate(BigDecimal.ZERO);
        row.setAnomalyCount(0L);
        row.setQualityScore(BigDecimal.ZERO);
        return row;
    }

    private static IotConsumerStatEntity consumerRow() {
        IotConsumerStatEntity entity = new IotConsumerStatEntity();
        entity.setId(1L);
        entity.setConsumerGroup(IotMessagingConstants.LOCAL_CONSUMER_GROUP);
        entity.setSampledAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBacklogEstimate(new BigDecimal("0.5000"));
        return entity;
    }

    private static <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> pageOf(T row) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(new java.util.ArrayList<>(List.of(row)));
        page.setTotal(1);
        return page;
    }
}

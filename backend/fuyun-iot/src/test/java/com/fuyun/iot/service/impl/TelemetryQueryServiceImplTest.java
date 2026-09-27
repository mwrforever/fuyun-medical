package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.iot.dto.TelemetrySeriesRequest;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.vo.BindingVO;
import com.fuyun.iot.vo.TelemetryLatestVO;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 遥测时序查询服务单元测试（FU-M14-06，brief Step 2 TDD）：三档路由判定（≤24h raw 明细 /
 * 超 24h 或显式 granularity 连续聚合 / 超 90 天强制 cagg_1h）、非法时窗与档位 IOT-1019、
 * patient 维度数据范围校验（resolve 状态判断）、最新值快照兜底解析。
 *
 * <p>路由断言锚：mock mapper 后按「哪个方法被调用 + 表名参数值」判定实际路由档位——
 * selectRawSeries=明细档、selectCaggSeries(tableName=iot.cagg_1min)=1 分钟聚合档、
 * tableName=iot.cagg_1h=1 小时聚合档。测试数据全部合成，与真实遥测无关。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryQueryServiceImplTest {

    private static final String METRIC = "MDC_ECG_HEART_RATE";

    @Mock
    private IotTelemetryMapper telemetryMapper;

    @Mock
    private IotBindingMapper bindingMapper;

    @Mock
    private IBindingService bindingService;

    @Mock
    private PatientContextResolver patientResolver;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<String> tableNameCaptor;

    private TelemetryQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TelemetryQueryServiceImpl(
                telemetryMapper, bindingMapper, bindingService, patientResolver, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ---------------------------------------------------------------- 三档路由判定

    @Test
    @DisplayName("路由档一：≤24h 且默认档走明细（selectRawSeries），不触连续聚合")
    void seriesWithin24hRoutesToRawDetail() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(24);
        when(telemetryMapper.selectRawSeries(eq(METRIC), eq(from), eq(to), eq("dev-01"), isNull(), isNull()))
                .thenReturn(List.of(point(from)));

        List<TelemetryPoint> points =
                service.series(new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, from, to, null));

        assertThat(points).hasSize(1);
        verify(telemetryMapper).selectRawSeries(eq(METRIC), eq(from), eq(to), eq("dev-01"), isNull(), isNull());
        verify(telemetryMapper, never()).selectCaggSeries(anyString(), anyString(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("路由档二：超 24h 默认档走 cagg_1min（明细档拒绝大范围扫描）")
    void seriesBeyond24hRoutesToCagg1min() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(3);
        when(telemetryMapper.selectCaggSeries(anyString(), eq(METRIC), eq(from), eq(to), anyCollection()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, from, to, null));

        verify(telemetryMapper)
                .selectCaggSeries(tableNameCaptor.capture(), eq(METRIC), eq(from), eq(to), eq(List.of("dev-01")));
        assertThat(tableNameCaptor.getValue()).isEqualTo("iot.cagg_1min");
    }

    @ParameterizedTest
    @DisplayName("显式 granularity 恒走对应连续聚合（1min→cagg_1min、1h→cagg_1h，即便窗口仅 1 小时）")
    @CsvSource({"1min, iot.cagg_1min", "1h, iot.cagg_1h"})
    void explicitGranularityRoutesToMatchingCagg(String granularity, String expectedTable) {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(1);
        when(telemetryMapper.selectCaggSeries(anyString(), eq(METRIC), eq(from), eq(to), anyCollection()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, from, to, granularity));

        verify(telemetryMapper)
                .selectCaggSeries(tableNameCaptor.capture(), eq(METRIC), eq(from), eq(to), eq(List.of("dev-01")));
        assertThat(tableNameCaptor.getValue()).isEqualTo(expectedTable);
    }

    @Test
    @DisplayName("路由档三：超 90 天强制 cagg_1h（即便显式 1min 或默认档）")
    void seriesBeyond90DaysForcesCagg1h() {
        OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(91);
        when(telemetryMapper.selectCaggSeries(anyString(), eq(METRIC), eq(from), eq(to), anyCollection()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, from, to, "1min"));

        verify(telemetryMapper)
                .selectCaggSeries(tableNameCaptor.capture(), eq(METRIC), eq(from), eq(to), eq(List.of("dev-01")));
        assertThat(tableNameCaptor.getValue()).isEqualTo("iot.cagg_1h");
        // 超 90 天明细档与 1 分钟聚合档均不得触碰（保留策略下明细已淘汰，查询保护红线）
        verify(telemetryMapper, never()).selectRawSeries(anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("显式 raw 超 24h 仍拒绝明细档（与默认档同守 24h 明细保护线）")
    void explicitRawBeyond24hStillRoutesToCagg() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(48);
        when(telemetryMapper.selectCaggSeries(anyString(), eq(METRIC), eq(from), eq(to), anyCollection()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, from, to, "raw"));

        verify(telemetryMapper)
                .selectCaggSeries(tableNameCaptor.capture(), eq(METRIC), eq(from), eq(to), eq(List.of("dev-01")));
        assertThat(tableNameCaptor.getValue()).isEqualTo("iot.cagg_1min");
        verify(telemetryMapper, never()).selectRawSeries(anyString(), any(), any(), anyString(), any(), any());
    }

    // ---------------------------------------------------------------- 非法入参 IOT-1019

    @Test
    @DisplayName("时窗非法（from 不早于 to）：IOT-1019 400，不触库")
    void nonPositiveWindowRejectedWithIot1019() {
        OffsetDateTime base = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.series(
                        new TelemetrySeriesRequest("device", "dev-01", null, null, METRIC, base, base, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
        verify(telemetryMapper, never()).selectRawSeries(anyString(), any(), any(), anyString(), any(), any());
    }

    @ParameterizedTest
    @DisplayName("档位非法（词表外 granularity）：IOT-1019 400，不触库")
    @ValueSource(strings = {"5min", "RAW", "day"})
    void unknownGranularityRejectedWithIot1019(String granularity) {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.series(new TelemetrySeriesRequest(
                        "device", "dev-01", null, null, METRIC, from, from.plusHours(1), granularity)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
    }

    @Test
    @DisplayName("维度标识缺失（scope=device 无 deviceId）：IOT-1019 400")
    void missingScopeIdentifierRejectedWithIot1019() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.series(
                        new TelemetrySeriesRequest("device", " ", null, null, METRIC, from, from.plusHours(1), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
    }

    // ---------------------------------------------------------------- patient 维度数据范围校验

    @Test
    @DisplayName("patient 维度正常档案：resolve 归一主档后按 patient_id 走明细查询")
    void patientScopeResolvesAndQueriesByPatientId() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(2);
        when(patientResolver.resolve(8001L)).thenReturn(new PatientContextView(8001L, 8001L, "NORMAL", false, ""));
        when(telemetryMapper.selectRawSeries(eq(METRIC), eq(from), eq(to), isNull(), eq(8001L), isNull()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("patient", null, 8001L, null, METRIC, from, to, null));

        verify(telemetryMapper).selectRawSeries(eq(METRIC), eq(from), eq(to), isNull(), eq(8001L), isNull());
    }

    @Test
    @DisplayName("patient 维度冻结/合并档案（数据范围校验不通过）：IOT-1019 400，不触库")
    void patientScopeBlockedOrMergedRejectedWithIot1019() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        when(patientResolver.resolve(8002L)).thenReturn(new PatientContextView(8002L, 8002L, "FROZEN", true, "欠费冻结"));

        assertThatThrownBy(() -> service.series(new TelemetrySeriesRequest(
                        "patient", null, 8002L, null, METRIC, from, from.plusHours(1), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
        verify(telemetryMapper, never()).selectRawSeries(anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("patient 维度超 24h 走聚合：resolve 通过后按绑定历史展开设备集合入 cagg")
    void patientScopeCaggExpandsDevicesFromBindingHistory() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(3);
        when(patientResolver.resolve(8001L)).thenReturn(new PatientContextView(8001L, 8001L, "NORMAL", false, ""));
        when(bindingMapper.selectDeviceIdsByPatientId(8001L)).thenReturn(List.of("dev-a", "dev-b"));
        when(telemetryMapper.selectCaggSeries(anyString(), eq(METRIC), eq(from), eq(to), anyCollection()))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("patient", null, 8001L, null, METRIC, from, to, null));

        verify(telemetryMapper)
                .selectCaggSeries(
                        tableNameCaptor.capture(), eq(METRIC), eq(from), eq(to), eq(List.of("dev-a", "dev-b")));
        assertThat(tableNameCaptor.getValue()).isEqualTo("iot.cagg_1min");
    }

    // ---------------------------------------------------------------- ward 维度与最新值兜底

    @Test
    @DisplayName("ward 维度：BOUND 绑定展开设备集合走明细档（≤24h），空绑定返回空清单不触库")
    void wardScopeExpandsBoundDevices() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(6);
        when(bindingService.listByWard(5L)).thenReturn(List.of(binding("dev-a"), binding("dev-b")));
        when(telemetryMapper.selectRawSeries(
                        eq(METRIC), eq(from), eq(to), isNull(), isNull(), eq(List.of("dev-a", "dev-b"))))
                .thenReturn(List.of());

        service.series(new TelemetrySeriesRequest("ward", null, null, 5L, METRIC, from, to, null));

        verify(telemetryMapper)
                .selectRawSeries(eq(METRIC), eq(from), eq(to), isNull(), isNull(), eq(List.of("dev-a", "dev-b")));
    }

    @Test
    @DisplayName("最新值快照兜底：解析 value|epochMilli 管道文本为数值与 UTC 时点")
    void latestParsesSnapshotPipeFormat() {
        OffsetDateTime occurred = OffsetDateTime.of(2026, 9, 26, 8, 30, 0, 0, ZoneOffset.UTC);
        when(valueOperations.get("fy:iot:snapshot:latest:dev-01:" + METRIC))
                .thenReturn("72.5|" + occurred.toInstant().toEpochMilli());

        TelemetryLatestVO vo = service.latest("dev-01", METRIC);

        assertThat(vo.deviceId()).isEqualTo("dev-01");
        assertThat(vo.value()).isEqualByComparingTo("72.5");
        assertThat(vo.occurredAt().toInstant().toEpochMilli())
                .isEqualTo(occurred.toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("最新值快照缺席（TTL 过期/从未上报）：返回 value/occurredAt 为 null 的视图不报错")
    void latestWithoutSnapshotReturnsEmptyView() {
        when(valueOperations.get(anyString())).thenReturn(null);

        TelemetryLatestVO vo = service.latest("dev-01", METRIC);

        assertThat(vo.value()).isNull();
        assertThat(vo.occurredAt()).isNull();
    }

    // ---------------------------------------------------------------- 消费端口形态与防御分支

    @Test
    @DisplayName("Port 单设备形态（ward 模块消费面）：单设备入参收敛 device 维度同一路由")
    void portFormSeriesRoutesAsDeviceScope() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusHours(12);
        when(telemetryMapper.selectRawSeries(eq(METRIC), eq(from), eq(to), eq("dev-port"), isNull(), isNull()))
                .thenReturn(List.of());

        List<TelemetryPoint> points =
                ((com.fuyun.iot.api.IotTelemetryQueryPort) service).series("dev-port", METRIC, from, to, "raw");

        assertThat(points).isEmpty();
        verify(telemetryMapper).selectRawSeries(eq(METRIC), eq(from), eq(to), eq("dev-port"), isNull(), isNull());
    }

    @ParameterizedTest
    @DisplayName("维度标识缺失（patient 无 patientId / ward 无 wardId）：IOT-1019 400")
    @CsvSource({"patient", "ward"})
    void missingPatientOrWardIdentifierRejectedWithIot1019(String scope) {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.series(
                        new TelemetrySeriesRequest(scope, null, null, null, METRIC, from, from.plusHours(1), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
    }

    @Test
    @DisplayName("词表外 scope 兜底（直调服务层绕过 @Pattern）：IOT-1019 400")
    void unknownScopeRejectedWithIot1019() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> service.series(new TelemetrySeriesRequest(
                        "dept", "dev-01", null, null, METRIC, from, from.plusHours(1), null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.TELEMETRY_QUERY_INVALID));
    }

    @Test
    @DisplayName("patient 聚合档无历史绑定设备：返回空清单不触聚合查询（防空 IN 非法 SQL）")
    void patientScopeCaggWithoutBindingHistoryReturnsEmpty() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 20, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(3);
        when(patientResolver.resolve(8003L)).thenReturn(new PatientContextView(8003L, 8003L, "NORMAL", false, ""));
        when(bindingMapper.selectDeviceIdsByPatientId(8003L)).thenReturn(List.of());

        List<TelemetryPoint> points =
                service.series(new TelemetrySeriesRequest("patient", null, 8003L, null, METRIC, from, to, null));

        assertThat(points).isEmpty();
        verify(telemetryMapper, never()).selectCaggSeries(anyString(), anyString(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("ward 维度空绑定：明细档与聚合档均返回空清单不触库")
    void wardScopeWithoutBoundDevicesReturnsEmpty() {
        OffsetDateTime from = OffsetDateTime.of(2026, 9, 26, 0, 0, 0, 0, ZoneOffset.UTC);
        when(bindingService.listByWard(9L)).thenReturn(List.of());

        assertThat(service.series(
                        new TelemetrySeriesRequest("ward", null, null, 9L, METRIC, from, from.plusHours(1), null)))
                .isEmpty();
        assertThat(service.series(
                        new TelemetrySeriesRequest("ward", null, null, 9L, METRIC, from, from.plusDays(3), null)))
                .isEmpty();
        verify(telemetryMapper, never()).selectRawSeries(anyString(), any(), any(), anyString(), any(), any());
        verify(telemetryMapper, never()).selectCaggSeries(anyString(), anyString(), any(), any(), anyCollection());
    }

    @Test
    @DisplayName("最新值快照 Redis 异常：辅助面降级返回空值视图不抛错")
    void latestRedisFailureDegradesToEmptyView() {
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        TelemetryLatestVO vo = service.latest("dev-01", METRIC);

        assertThat(vo.value()).isNull();
        assertThat(vo.occurredAt()).isNull();
    }

    @Test
    @DisplayName("最新值快照形态漂移（无管道分隔符）与数值不可解析：均按缺席处置返回空值视图")
    void latestMalformedSnapshotDegradesToEmptyView() {
        when(valueOperations.get("fy:iot:snapshot:latest:dev-01:" + METRIC)).thenReturn("no-pipe-marker");
        assertThat(service.latest("dev-01", METRIC).value()).isNull();

        when(valueOperations.get("fy:iot:snapshot:latest:dev-01:" + METRIC)).thenReturn("abc|not-a-millis");
        assertThat(service.latest("dev-01", METRIC).occurredAt()).isNull();
    }

    // ---------------------------------------------------------------- 测试夹具

    private static TelemetryPoint point(OffsetDateTime time) {
        return new TelemetryPoint(
                "dev-01",
                METRIC,
                time,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                1L);
    }

    private static BindingVO binding(String deviceId) {
        return new BindingVO(
                1L,
                deviceId,
                8001L,
                "V20260901000001",
                11L,
                5L,
                null,
                null,
                null,
                null,
                "system",
                OffsetDateTime.now(),
                null);
    }
}

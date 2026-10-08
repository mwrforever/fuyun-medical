package com.fuyun.ops.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingWorkloadStats;
import com.fuyun.billing.api.BillingWorkloadStats.PendingFeeEvent;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.nursing.api.NursingWorkloadStats;
import com.fuyun.ops.constants.OpsConstants;
import com.fuyun.ops.vo.WorkbenchEventsVO;
import com.fuyun.ops.vo.WorkbenchEventsVO.Topic;
import com.fuyun.ops.vo.WorkbenchOverviewVO;
import com.fuyun.ops.vo.WorkbenchOverviewVO.Metrics;
import com.fuyun.ops.vo.WorkbenchOverviewVO.TrendPoint;
import com.fuyun.ops.vo.WorkbenchOverviewVO.WaitingRow;
import com.fuyun.outpatient.api.OutpatientWorkloadStats;
import com.fuyun.outpatient.api.OutpatientWorkloadStats.DailyVisitTrendRow;
import com.fuyun.outpatient.api.OutpatientWorkloadStats.DeptWaitingRow;
import com.fuyun.outpatient.api.TrendWindow;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats;
import com.fuyun.pharmacy.api.PharmacyWorkloadStats.PendingDispenseEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 运营工作台聚合服务单测（批次 2 册 2 用例组）：①overview 四 Port 聚合组装与 VO 映射
 * （六格/趋势/候诊表逐字段）；②缓存 read-through 命中直返/穿透回写（TTL 5s 显式断言）/
 * JSON 损坏与 Redis 读异常降级直算/写异常不阻断；③events 五源装配（三主题指引+双源合并
 * 降序有界+危急值恒空段与降级标志）；④全空数据零值视图。JaCoCo ops bundle 红线：本类承载
 * OpsWorkbenchServiceImpl 全部分支面（册 2 验收线：聚合 service 单测 100%）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsWorkbenchServiceImplTest {

    /** 快照缓存键（与实现常量同源断言） */
    private static final String SNAPSHOT_KEY = "fy:ops:snapshot:workbench:overview";

    @Mock
    private com.fuyun.outpatient.api.OutpatientStatsPort outpatientStatsPort;

    @Mock
    private com.fuyun.billing.api.BillingStatsPort billingStatsPort;

    @Mock
    private com.fuyun.pharmacy.api.PharmacyStatsPort pharmacyStatsPort;

    @Mock
    private com.fuyun.nursing.api.NursingStatsPort nursingStatsPort;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /** JSON 转换器（Boot 容器同构：findAndRegisterModules 注册 jsr310——OffsetDateTime 序列化前提） */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OpsWorkbenchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OpsWorkbenchServiceImpl(
                outpatientStatsPort,
                billingStatsPort,
                pharmacyStatsPort,
                nursingStatsPort,
                redisTemplate,
                objectMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("①overview 四 Port 聚合：六格逐字段+趋势映射+候诊表映射+穿透回写 TTL 5s")
    void overviewComputesFromFourPortsAndWritesCacheWithTtl5s() {
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(null);
        stubPorts(fullOutpatient(), fullBilling(), fullPharmacy(), new NursingWorkloadStats(86L));

        WorkbenchOverviewVO overview = service.overview();

        // 六格逐字段断言（OutpatientWorkloadStats → Metrics 映射口径）
        Metrics metrics = overview.metrics();
        assertThat(metrics.todayVisits()).isEqualTo(128L);
        assertThat(metrics.waitingCount()).isEqualTo(23L);
        assertThat(metrics.todayIncomeFen()).isEqualTo(4567800L);
        assertThat(metrics.inHospitalCount()).isEqualTo(86L);
        assertThat(metrics.pendingDispenseCount()).isEqualTo(12L);
        assertThat(metrics.pendingSettleCount()).isEqualTo(34L);
        // 趋势与候诊表直传映射
        assertThat(overview.trend()).hasSize(2);
        TrendPoint trendPoint = overview.trend().get(0);
        assertThat(trendPoint.statDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(trendPoint.visitCount()).isEqualTo(120L);
        assertThat(trendPoint.emergencyCount()).isEqualTo(8L);
        assertThat(overview.waitingTable()).hasSize(2);
        WaitingRow waitingRow = overview.waitingTable().get(0);
        assertThat(waitingRow.deptCode()).isEqualTo("D02");
        assertThat(waitingRow.longestWaitingMinutes()).isEqualTo(90L);
        assertThat(overview.generatedAt()).isNotNull();
        // 穿透回写：显式 TTL 5s（禁无 TTL 键红线断言）
        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.eq(SNAPSHOT_KEY),
                        org.mockito.ArgumentMatchers.contains("todayVisits"),
                        org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("②缓存命中直返：不再触达四 Port（读路径不触库口径）")
    void overviewReturnsCachedSnapshotWithoutRecomputing() throws Exception {
        WorkbenchOverviewVO cached =
                new WorkbenchOverviewVO(new Metrics(1, 2, 3, 4, 5, 6), List.of(), List.of(), OffsetDateTime.now());
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(objectMapper.writeValueAsString(cached));

        WorkbenchOverviewVO overview = service.overview();

        assertThat(overview.metrics().todayVisits()).isEqualTo(1L);
        assertThat(overview.metrics().pendingSettleCount()).isEqualTo(6L);
        org.mockito.Mockito.verifyNoInteractions(
                outpatientStatsPort, billingStatsPort, pharmacyStatsPort, nursingStatsPort);
    }

    @Test
    @DisplayName("③缓存 JSON 损坏降级直算：warn 降级后回写覆盖损坏值")
    void overviewFallsBackToComputeOnCorruptedCache() {
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{not-json");
        stubPorts(fullOutpatient(), fullBilling(), fullPharmacy(), new NursingWorkloadStats(86L));

        WorkbenchOverviewVO overview = service.overview();

        assertThat(overview.metrics().todayVisits()).isEqualTo(128L);
    }

    @Test
    @DisplayName("④Redis 读异常降级直算：连接失败不阻断聚合主链")
    void overviewFallsBackToComputeOnRedisReadFailure() {
        when(valueOperations.get(SNAPSHOT_KEY)).thenThrow(new RedisConnectionFailureException("down"));
        stubPorts(fullOutpatient(), fullBilling(), fullPharmacy(), new NursingWorkloadStats(86L));

        WorkbenchOverviewVO overview = service.overview();

        assertThat(overview.metrics().todayVisits()).isEqualTo(128L);
    }

    @Test
    @DisplayName("⑤缓存写异常不阻断：聚合结果照常返回（缓存可重建语义）")
    void overviewReturnsResultWhenCacheWriteFails() {
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(null);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations)
                .set(any(), any(), any(Duration.class));
        stubPorts(fullOutpatient(), fullBilling(), fullPharmacy(), new NursingWorkloadStats(86L));

        WorkbenchOverviewVO overview = service.overview();

        assertThat(overview.metrics().todayVisits()).isEqualTo(128L);
    }

    @Test
    @DisplayName("⑥events 五源装配：三主题指引+双源合并降序有界+危急值恒空段带降级标志")
    void eventsAssemblesFiveSourcesWithDegradedCriticalValues() {
        OffsetDateTime earlier = OffsetDateTime.of(2026, 10, 9, 9, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime later = OffsetDateTime.of(2026, 10, 9, 10, 0, 0, 0, ZoneOffset.UTC);
        when(billingStatsPort.workloadStats(any()))
                .thenReturn(new BillingWorkloadStats(
                        LocalDate.now(TimeConstants.HEALTHCARE_TZ),
                        100L,
                        1L,
                        List.of(new PendingFeeEvent("F1001", "血常规", 3500L, later))));
        when(pharmacyStatsPort.pendingStats())
                .thenReturn(new PharmacyWorkloadStats(
                        12L, List.of(new PendingDispenseEvent("D1000001", "RX202610090001", earlier))));

        WorkbenchEventsVO events = service.events();

        // 三主题指引：/ws/iot、/ws/nursing、/ws/outpatient 三既有端点各一条（复用不新建）
        assertThat(events.topics())
                .extracting(Topic::endpoint)
                .containsExactly("/ws/iot", "/ws/nursing", "/ws/outpatient");
        // 双源合并降序：后时点的待支付在前
        assertThat(events.events()).hasSize(2);
        assertThat(events.events().get(0).id()).isEqualTo("F1001");
        assertThat(events.events().get(0).type()).isEqualTo(OpsConstants.EVENT_TYPE_FEE_PENDING);
        assertThat(events.events().get(0).source()).isEqualTo("billing");
        assertThat(events.events().get(0).amountFen()).isEqualTo(3500L);
        assertThat(events.events().get(1).id()).isEqualTo("D1000001");
        assertThat(events.events().get(1).type()).isEqualTo(OpsConstants.EVENT_TYPE_DISPENSE_PENDING);
        assertThat(events.events().get(1).amountFen()).isNull();
        // 危急值缺位降级：恒空数组+降级标志（M07 未建——前端降级文案判别面）
        assertThat(events.criticalValues()).isEmpty();
        assertThat(events.criticalValueDegraded()).isTrue();
        assertThat(events.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("⑦事件合并总上限截断：超 EVENTS_LIMIT 只保留最近有界清单")
    void eventsTruncatesMergedListToLimit() {
        List<PendingFeeEvent> fees = new java.util.ArrayList<>();
        for (int i = 0; i < OpsConstants.EVENTS_LIMIT + 10; i++) {
            fees.add(new PendingFeeEvent(
                    "F" + i,
                    "项目" + i,
                    100L,
                    OffsetDateTime.of(2026, 10, 9, 8, 0, 0, 0, ZoneOffset.UTC).minusMinutes(i)));
        }
        when(billingStatsPort.workloadStats(any()))
                .thenReturn(new BillingWorkloadStats(LocalDate.now(TimeConstants.HEALTHCARE_TZ), 100L, 1L, fees));
        when(pharmacyStatsPort.pendingStats()).thenReturn(new PharmacyWorkloadStats(0L, List.of()));

        WorkbenchEventsVO events = service.events();

        assertThat(events.events()).hasSize(OpsConstants.EVENTS_LIMIT);
        // 截断保留最近时点：首行为最大时刻行
        assertThat(events.events().get(0).id()).isEqualTo("F0");
    }

    @Test
    @DisplayName("⑧全空数据零值视图：四 Port 零返回出零值/空清单，不造数")
    void overviewReturnsZeroViewOnAllEmptyPorts() {
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(null);
        stubPorts(
                new OutpatientWorkloadStats(
                        new TrendWindow(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 9)),
                        0L,
                        0L,
                        List.of(),
                        List.of()),
                new BillingWorkloadStats(LocalDate.now(TimeConstants.HEALTHCARE_TZ), 0L, 0L, List.of()),
                new PharmacyWorkloadStats(0L, List.of()),
                new NursingWorkloadStats(0L));

        WorkbenchOverviewVO overview = service.overview();

        Metrics metrics = overview.metrics();
        assertThat(metrics.todayVisits()).isZero();
        assertThat(metrics.waitingCount()).isZero();
        assertThat(metrics.todayIncomeFen()).isZero();
        assertThat(metrics.inHospitalCount()).isZero();
        assertThat(metrics.pendingDispenseCount()).isZero();
        assertThat(metrics.pendingSettleCount()).isZero();
        assertThat(overview.trend()).isEmpty();
        assertThat(overview.waitingTable()).isEmpty();

        WorkbenchEventsVO events = service.events();
        assertThat(events.events()).isEmpty();
        assertThat(events.criticalValues()).isEmpty();
        assertThat(events.criticalValueDegraded()).isTrue();
    }

    /**
     * 四 Port 打桩（参数无关桩——窗口/日期由实现内部计算）。
     */
    private void stubPorts(
            OutpatientWorkloadStats outpatient,
            BillingWorkloadStats billing,
            PharmacyWorkloadStats pharmacy,
            NursingWorkloadStats nursing) {
        when(outpatientStatsPort.workloadStats(any(TrendWindow.class))).thenReturn(outpatient);
        when(billingStatsPort.workloadStats(any(LocalDate.class))).thenReturn(billing);
        when(pharmacyStatsPort.pendingStats()).thenReturn(pharmacy);
        when(nursingStatsPort.inHospitalStats()).thenReturn(nursing);
    }

    /** 全量门诊段桩（两日趋势+两科室候诊表） */
    private OutpatientWorkloadStats fullOutpatient() {
        return new OutpatientWorkloadStats(
                new TrendWindow(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 9)),
                128L,
                23L,
                List.of(new DeptWaitingRow("D02", 5L, 90L), new DeptWaitingRow("D01", 2L, 30L)),
                List.of(
                        new DailyVisitTrendRow(LocalDate.of(2026, 10, 8), 120L, 8L),
                        new DailyVisitTrendRow(LocalDate.of(2026, 10, 9), 128L, 10L)));
    }

    /** 全量收费段桩（收入/待结算/待支付事件） */
    private BillingWorkloadStats fullBilling() {
        return new BillingWorkloadStats(
                LocalDate.now(TimeConstants.HEALTHCARE_TZ),
                4567800L,
                34L,
                List.of(new PendingFeeEvent(
                        "F1001", "血常规", 3500L, OffsetDateTime.of(2026, 10, 9, 10, 30, 0, 0, ZoneOffset.UTC))));
    }

    /** 全量药段桩（待配药计数+事件） */
    private PharmacyWorkloadStats fullPharmacy() {
        return new PharmacyWorkloadStats(
                12L,
                List.of(new PendingDispenseEvent(
                        "D1000001", "RX202610090001", OffsetDateTime.of(2026, 10, 9, 9, 15, 0, 0, ZoneOffset.UTC))));
    }
}

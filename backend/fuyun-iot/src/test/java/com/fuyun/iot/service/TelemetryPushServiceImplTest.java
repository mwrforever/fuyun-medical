package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.internal.TelemetrySummaryAggregator;
import com.fuyun.iot.service.impl.TelemetryPushServiceImpl;
import com.fuyun.iot.vo.DashboardSummaryVO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 遥测 STOMP 推送服务单元测试（BRIEF-PR4-01 §4 核心包成员 + P2 PR-2 Task 11 四主题完整化）。
 *
 * <p>覆盖：wardId null 跳过（遥测摘要/设备状态两方法）、2s 窗口节流三分支（单批延迟到窗口
 * 排空、窗口内多批合并单帧、items 上限截断）、offer 触发排空路径、dashboard/global 全院主题
 * 推送载荷、设备状态载荷与事件同构。真实 broker 链路（订阅收帧）归 IotTelemetryPipelineIT
 * 步骤 3/5。
 *
 * <p>窗口边界以 SteppingClock 注入（brief「时钟注入」口径），测试内显式推进驱动排空；
 * SmartLifecycle flush 线程不在单测启动（排空入口 drainExpired 直驱断言）。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryPushServiceImplTest {

    /** 测试病区 ID：主题路径断言值 */
    private static final long WARD_ID = 1001L;

    /** 摘要条数上限同值引用（与接口常量对齐断言，防实现漂移） */
    private static final int SUMMARY_MAX_ITEMS = ITelemetryPushService.SUMMARY_MAX_ITEMS;

    /** 窗口起点锚（UTC，测试推进基准） */
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Captor
    private ArgumentCaptor<Object> payloadCaptor;

    /** 可推进时钟：窗口边界注入载体 */
    private TelemetryPushServiceImplTest.SteppingClock clock;

    private TelemetrySummaryAggregator aggregator;

    private TelemetryPushServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new TelemetryPushServiceImplTest.SteppingClock(T0);
        aggregator = new TelemetrySummaryAggregator(clock);
        service = new TelemetryPushServiceImpl(messagingTemplate, aggregator);
    }

    // ---------------------------------------------------------------- 遥测摘要 2s 窗口节流

    @Test
    @DisplayName("遥测摘要推送：wardId 为 null 跳过（无绑定快照的帧仅落库不推送）")
    void skipsSummaryPushWhenWardIdIsNull() {
        service.pushSummary(List.of(telemetry(1L, "MDC_ECG_HEART_RATE")), null);

        verifyNoInteractions(messagingTemplate);
        assertThat(aggregator.drainExpired()).as("跳过批不进窗").isEmpty();
    }

    @Test
    @DisplayName("遥测摘要推送：批次为 null 的防御跳过（计数留痕 0，不进窗）")
    void skipsSummaryPushWhenBatchIsNull() {
        service.pushSummary(null, null);

        verifyNoInteractions(messagingTemplate);
        assertThat(aggregator.drainExpired()).as("防御批不进窗").isEmpty();
    }

    @Test
    @DisplayName("2s 窗口节流：单批不即时产帧，窗口到期由后续到批触发排空，载荷含条数/上界/明细")
    void defersSingleBatchUntilWindowDrained() {
        IotTelemetryEntity first = telemetry(1L, "MDC_ECG_HEART_RATE");
        IotTelemetryEntity second = telemetry(2L, "MDC_SPO2");
        Instant expectedUpperBound = Instant.parse("2026-09-26T08:00:02Z");
        second.setOccurredAt(OffsetDateTime.ofInstant(expectedUpperBound, ZoneOffset.UTC));

        service.pushSummary(List.of(first, second), WARD_ID);
        verifyNoInteractions(messagingTemplate);

        clock.advance(Duration.ofSeconds(3));
        // 触发批（另一设备）：offer 前置排空到期窗口——上一窗以单帧发出，触发批自身进新窗不随发
        service.pushSummary(List.of(telemetry(90L, "MDC_ECG_HEART_RATE")), WARD_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isInstanceOf(ITelemetryPushService.TelemetrySummary.class);
        ITelemetryPushService.TelemetrySummary summary =
                (ITelemetryPushService.TelemetrySummary) payloadCaptor.getValue();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.occurredAtUpperBound()).as("occurredAt 上界取窗口内最大发生时刻").isEqualTo(expectedUpperBound);
        assertThat(summary.items()).hasSize(2);
        assertThat(summary.items().get(0).deviceId()).isEqualTo("dev-001");
        assertThat(summary.items().get(0).metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(summary.items().get(1).metricCode()).isEqualTo("MDC_SPO2");
    }

    @Test
    @DisplayName("窗口内多批合并单帧：两批同床位（病区）归并推送一帧，条数累计、明细去重")
    void mergesTwoBatchesWithinWindowIntoSingleFrame() {
        service.pushSummary(List.of(telemetry(1L, "MDC_ECG_HEART_RATE")), WARD_ID);
        clock.advance(Duration.ofSeconds(1));
        service.pushSummary(List.of(telemetry(1L, "MDC_ECG_HEART_RATE"), telemetry(2L, "MDC_SPO2")), WARD_ID);
        verifyNoInteractions(messagingTemplate);

        clock.advance(Duration.ofSeconds(2));
        service.pushSummary(List.of(telemetry(9L, "MDC_ECG_HEART_RATE")), WARD_ID);

        // 第三批触发达期排空：前两批合并单帧（第三批进新窗不随发）
        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), payloadCaptor.capture());
        ITelemetryPushService.TelemetrySummary summary =
                (ITelemetryPushService.TelemetrySummary) payloadCaptor.getValue();
        assertThat(summary.count()).as("条数 = 两批真实累计（3）").isEqualTo(3);
        assertThat(summary.items()).as("明细去重（dev-001 心率两现合一）").hasSize(2);
    }

    @Test
    @DisplayName("摘要 items 上限截断：超限窗口 items 截至上限而条数保留真实值（防大消息）")
    void truncatesSummaryItemsWhenWindowExceedsLimit() {
        List<IotTelemetryEntity> oversized = LongStream.rangeClosed(1, SUMMARY_MAX_ITEMS + 50)
                .mapToObj(seq -> telemetry(seq, "MDC_ECG_HEART_RATE"))
                .toList();

        service.pushSummary(oversized, WARD_ID);
        clock.advance(Duration.ofSeconds(3));
        // 触发批：offer 前置排空超限窗口，发送侧截断 items
        service.pushSummary(List.of(telemetry(90L, "MDC_ECG_HEART_RATE")), WARD_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), payloadCaptor.capture());
        ITelemetryPushService.TelemetrySummary summary =
                (ITelemetryPushService.TelemetrySummary) payloadCaptor.getValue();
        assertThat(summary.count()).as("条数为真实累计，不随 items 截断").isEqualTo(SUMMARY_MAX_ITEMS + 50);
        assertThat(summary.items()).as("items 明细截至上限（载荷轻量化）").hasSize(SUMMARY_MAX_ITEMS);
    }

    // ---------------------------------------------------------------- 设备状态/告警/联动（既有冻结面回归）

    @Test
    @DisplayName("设备状态推送：wardId 为 null 跳过（P0 状态帧契约不含 wardId，推送静默降级）")
    void skipsStatusPushWhenWardIdIsNull() {
        DeviceStatusEvent event =
                new DeviceStatusEvent("dev-001", DeviceStatus.OFFLINE, Instant.parse("2026-09-10T04:05:06Z"), null);

        service.pushDeviceStatus(event);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("设备状态推送：推至 /topic/iot/device-status/{wardId}，载荷与事件契约同构")
    void pushesDeviceStatusToWardTopicWithEventPayload() {
        DeviceStatusEvent event =
                new DeviceStatusEvent("dev-001", DeviceStatus.OFFLINE, Instant.parse("2026-09-10T04:05:06Z"), WARD_ID);

        service.pushDeviceStatus(event);

        verify(messagingTemplate).convertAndSend(anyString(), payloadCaptor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/iot/device-status/" + WARD_ID), eq(event));
        assertThat(payloadCaptor.getValue()).isSameAs(event);
    }

    @Test
    @DisplayName("全院运营摘要推送（Task 11 四主题完整化）：载荷 = DashboardSummaryVO 直推 global 主题")
    void pushesDashboardSummaryToGlobalTopic() {
        DashboardSummaryVO summary = new DashboardSummaryVO(10, 3, 1, 2, true, null, null);

        service.pushDashboardSummary(summary);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/dashboard/global"), eq(summary));
    }

    /** 构造遥测实体（quality/source 取 GOOD/IOTDA 与解析器缺省产物一致；occurredAt 递增区分上界断言） */
    private static IotTelemetryEntity telemetry(long seq, String metricCode) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId("dev-" + String.format("%03d", seq));
        entity.setMetricCode(metricCode);
        entity.setOccurredAt(
                OffsetDateTime.ofInstant(Instant.parse("2026-09-26T08:00:00Z").plusSeconds(seq), ZoneOffset.UTC));
        entity.setQuality(TelemetryQuality.GOOD);
        return entity;
    }

    @Test
    @DisplayName("告警帧推送（Task 7）：载荷 = triggered 契约 record 且字段与告警行一一对应，主题按病区路由")
    void pushAlarmSendsTriggeredPayloadToWardTopic() {
        com.fuyun.iot.entity.IotAlarmEntity alarm = alarmEntity();
        Instant occurredAt = Instant.parse("2026-09-26T08:00:00Z");
        alarm.setLastTriggeredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));

        service.pushAlarm(alarm);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/alarm/" + WARD_ID), payloadCaptor.capture());
        com.fuyun.iot.api.payload.AlarmTriggeredPayload payload =
                (com.fuyun.iot.api.payload.AlarmTriggeredPayload) payloadCaptor.getValue();
        assertThat(payload.alarmNo()).isEqualTo("AL2026092600001");
        assertThat(payload.deviceId()).isEqualTo("dev-001");
        assertThat(payload.patientId()).isEqualTo(5L);
        assertThat(payload.visitId()).isEqualTo("20260901000001");
        assertThat(payload.wardId()).isEqualTo(WARD_ID);
        assertThat(payload.alarmLevel()).isEqualTo("CRITICAL");
        assertThat(payload.metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(payload.triggerValue()).isEqualTo("170");
        assertThat(payload.ruleId()).isEqualTo(900001L);
        assertThat(payload.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("告警帧推送：wardId 为 null 跳过（引擎侧病区路由缺失防御口径）")
    void pushAlarmSkipsWhenWardMissing() {
        com.fuyun.iot.entity.IotAlarmEntity alarm = alarmEntity();
        alarm.setWardId(null);

        service.pushAlarm(alarm);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("联动强提醒推送（Task 9）：载荷与告警帧同构且 linkageNo 经 STOMP 头携带（带 linkage 标记）")
    void pushLinkageNotifySendsTriggeredPayloadWithLinkageHeader() {
        com.fuyun.iot.entity.IotAlarmEntity alarm = alarmEntity();
        Instant occurredAt = Instant.parse("2026-09-26T08:00:00Z");
        alarm.setLastTriggeredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));

        service.pushLinkageNotify(alarm, "LG2026092600001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(messagingTemplate)
                .convertAndSend(eq("/topic/iot/alarm/" + WARD_ID), payloadCaptor.capture(), headersCaptor.capture());
        com.fuyun.iot.api.payload.AlarmTriggeredPayload payload =
                (com.fuyun.iot.api.payload.AlarmTriggeredPayload) payloadCaptor.getValue();
        // 载荷与告警帧同构（重复强化语义，冻结契约不变）
        assertThat(payload.alarmNo()).isEqualTo("AL2026092600001");
        assertThat(payload.wardId()).isEqualTo(WARD_ID);
        assertThat(payload.occurredAt()).isEqualTo(occurredAt);
        // 联动标记经 STOMP 消息头携带
        assertThat(headersCaptor.getValue()).containsEntry("linkageNo", "LG2026092600001");
    }

    @Test
    @DisplayName("联动强提醒推送：wardId 为 null 跳过（与告警帧同口径，执行器按回执裁决）")
    void pushLinkageNotifySkipsWhenWardMissing() {
        com.fuyun.iot.entity.IotAlarmEntity alarm = alarmEntity();
        alarm.setWardId(null);

        service.pushLinkageNotify(alarm, "LG2026092600001");

        verifyNoInteractions(messagingTemplate);
    }

    /** 告警行夹具（心率危急告警，绑定快照五元组冗余） */
    private static com.fuyun.iot.entity.IotAlarmEntity alarmEntity() {
        com.fuyun.iot.entity.IotAlarmEntity entity = new com.fuyun.iot.entity.IotAlarmEntity();
        entity.setAlarmNo("AL2026092600001");
        entity.setRuleId(900001L);
        entity.setDeviceId("dev-001");
        entity.setPatientId(5L);
        entity.setVisitId("20260901000001");
        entity.setWardId(WARD_ID);
        entity.setAlarmLevel(com.fuyun.iot.enums.AlarmLevel.CRITICAL);
        entity.setMetricCode("MDC_ECG_HEART_RATE");
        entity.setTriggerValue("170");
        entity.setStatus(com.fuyun.iot.enums.AlarmStatus.ACTIVE);
        return entity;
    }

    /** 可推进固定时区时钟（窗口边界注入载体；生产装配为 IotWebSocketConfig 装配点显式构造 UTC 时钟） */
    private static final class SteppingClock extends Clock {

        private volatile Instant current;

        SteppingClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration step) {
            current = current.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

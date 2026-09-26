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
import com.fuyun.iot.service.impl.TelemetryPushServiceImpl;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * 遥测 STOMP 推送服务单元测试（BRIEF-PR4-01 §4 核心包成员，单测全覆盖）。
 *
 * <p>覆盖：wardId null 跳过（遥测摘要/设备状态两方法）、正常推送的主题路径与载荷字段断言
 * （摘要 record 的条数/occurredAt 上界/items 明细、设备状态载荷与事件同构）、摘要 items 上限
 * 截断（防 1009 大消息——载荷轻量化口径）。真实 broker 链路（订阅收帧）归
 * IotTelemetryPipelineIT 步骤 3/5。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryPushServiceImplTest {

    /** 测试病区 ID：主题路径断言值 */
    private static final long WARD_ID = 1001L;

    /** 摘要条数上限同值引用（与接口常量对齐断言，防实现漂移） */
    private static final int SUMMARY_MAX_ITEMS = ITelemetryPushService.SUMMARY_MAX_ITEMS;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Captor
    private ArgumentCaptor<Object> payloadCaptor;

    private TelemetryPushServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TelemetryPushServiceImpl(messagingTemplate);
    }

    @Test
    @DisplayName("遥测摘要推送：wardId 为 null 跳过（无绑定快照的帧仅落库不推送）")
    void skipsSummaryPushWhenWardIdIsNull() {
        service.pushSummary(List.of(telemetry(1L, "MDC_ECG_HEART_RATE")), null);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("遥测摘要推送：推至 /topic/iot/telemetry/{wardId}，载荷含条数/occurredAt 上界/items 明细")
    void pushesSummaryToWardTopicWithLightPayload() {
        IotTelemetryEntity first = telemetry(1L, "MDC_ECG_HEART_RATE");
        IotTelemetryEntity second = telemetry(2L, "MDC_SPO2");
        Instant expectedUpperBound = Instant.parse("2026-09-10T05:06:07Z");
        second.setOccurredAt(OffsetDateTime.ofInstant(expectedUpperBound, ZoneOffset.UTC));

        service.pushSummary(List.of(first, second), WARD_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isInstanceOf(ITelemetryPushService.TelemetrySummary.class);
        ITelemetryPushService.TelemetrySummary summary =
                (ITelemetryPushService.TelemetrySummary) payloadCaptor.getValue();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.occurredAtUpperBound()).as("occurredAt 上界取本批最大发生时刻").isEqualTo(expectedUpperBound);
        assertThat(summary.items()).hasSize(2);
        assertThat(summary.items().get(0).deviceId()).isEqualTo("dev-001");
        assertThat(summary.items().get(0).metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(summary.items().get(1).metricCode()).isEqualTo("MDC_SPO2");
    }

    @Test
    @DisplayName("摘要 items 上限截断：超限批次 items 截至上限而条数保留真实值（防大消息）")
    void truncatesSummaryItemsWhenBatchExceedsLimit() {
        List<IotTelemetryEntity> oversized = LongStream.rangeClosed(1, SUMMARY_MAX_ITEMS + 50)
                .mapToObj(seq -> telemetry(seq, "MDC_ECG_HEART_RATE"))
                .toList();

        service.pushSummary(oversized, WARD_ID);

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), payloadCaptor.capture());
        ITelemetryPushService.TelemetrySummary summary =
                (ITelemetryPushService.TelemetrySummary) payloadCaptor.getValue();
        assertThat(summary.count()).as("条数为真实批大小，不随 items 截断").isEqualTo(SUMMARY_MAX_ITEMS + 50);
        assertThat(summary.items()).as("items 明细截至上限（载荷轻量化）").hasSize(SUMMARY_MAX_ITEMS);
    }

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

    /** 构造遥测实体（quality/source 取 GOOD/IOTDA 与解析器缺省产物一致；occurredAt 递增区分上界断言） */
    private static IotTelemetryEntity telemetry(long seq, String metricCode) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId("dev-" + String.format("%03d", seq));
        entity.setMetricCode(metricCode);
        entity.setOccurredAt(
                OffsetDateTime.ofInstant(Instant.parse("2026-09-10T04:00:00Z").plusSeconds(seq), ZoneOffset.UTC));
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
}

package com.fuyun.ward.internal;

import static com.fuyun.ward.constants.WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.ward.cache.WardSeqGate;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 输液告急消费监听器单测（P2 PR-2 Task 12 Step 5，ward 侧 IotAlarmEventListener——与 iot 侧
 * Task 9 同名类以包路径区分）：标准三段式接入、输液告急判定（metricCode=INFUSION_SHORTAGE 且
 * trigger_value≤5 → 落 ward_call 系统级行）、三档外跳过、同告警活跃行防重、载荷契约不符按消费
 * 失败处置、幂等域 consumer_module=ward。
 */
@ExtendWith(MockitoExtension.class)
class IotAlarmEventListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "c5f2e4a6-7d81-4b93-9d04-2f5e6a7b8c01";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-26T07:00:00Z");

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private WardCallMapper callMapper;

    @Mock
    private WardSeqGate seqGate;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    private IotAlarmEventListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new IotAlarmEventListener(
                new IdempotentConsumerSupport(
                        idempotencyService, new EventEnvelopeCodec(objectMapper), WardMessagingConstants.MODULE),
                objectMapper,
                callMapper,
                seqGate);
    }

    @Test
    @DisplayName("重复投递跳过：tryAcquire 返回 false 直接返回（AUTO 确认），不触达落行与登记")
    void skipsRedeliveredMessageWithoutBusinessOrRecord() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onAlarmTriggered(message(envelope(payload("AL2026092600001", "4"))));

        verify(idempotencyService, never()).recordProcessed(any());
        verify(callMapper, never()).insert(any(WardCallEntity.class));
    }

    @Test
    @DisplayName("输液告急 5ml 档：落系统级呼叫行（call_type=INFUSION、source_ref=告警号、幂等域 ward）")
    void landsSystemLevelCallForInfusionShortageAtRedThreshold() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);
        when(callMapper.countActiveInfusionBySourceRef("AL2026092600001")).thenReturn(0L);
        when(seqGate.nextCallNo()).thenReturn("CALL2026092600002");

        listener.onAlarmTriggered(message(envelope(payload("AL2026092600001", "4"))));

        verify(callMapper).insert(callCaptor.capture());
        WardCallEntity entity = callCaptor.getValue();
        assertThat(entity.getCallNo()).isEqualTo("CALL2026092600002");
        assertThat(entity.getWardId()).isEqualTo(1001L);
        assertThat(entity.getDeviceId()).isEqualTo("dev-pump-1");
        assertThat(entity.getCallType()).isEqualTo(CallType.INFUSION);
        assertThat(entity.getSourceRef()).isEqualTo("AL2026092600001");
        assertThat(entity.getBedId()).as("告警载荷无床位锚（V1100 可空申报）").isNull();
        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        assertThat(recordCaptor.getValue().consumerModule()).isEqualTo(WardMessagingConstants.MODULE);
        assertThat(recordCaptor.getValue().eventType()).isEqualTo(WardMessagingConstants.EVENT_IOT_ALARM_TRIGGERED);
    }

    @Test
    @DisplayName("档位外（>5ml）跳过：metricCode 匹配但余量未到红档，留痕不落行")
    void skipsShortageAboveRedThreshold() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onAlarmTriggered(message(envelope(payload("AL2026092600001", "12"))));

        verify(callMapper, never()).insert(any(WardCallEntity.class));
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("非输液指标跳过：metricCode 不匹配直接留痕，零落行")
    void skipsNonInfusionMetric() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onAlarmTriggered(message(envelope(new AlarmTriggeredPayload(
                "AL2026092600002",
                "dev-ecg-1",
                5L,
                "20260901000001",
                1001L,
                "CRITICAL",
                "MDC_ECG_HEART_RATE",
                "155",
                77L,
                OCCURRED_AT))));

        verify(callMapper, never()).insert(any(WardCallEntity.class));
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("同告警活跃行防重：同 source_ref 活跃输液行已存在不重复落行（uk 部分唯一索引应用层前置）")
    void skipsDuplicateActiveCallForSameAlarm() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);
        when(callMapper.countActiveInfusionBySourceRef("AL2026092600001")).thenReturn(1L);

        listener.onAlarmTriggered(message(envelope(payload("AL2026092600001", "4"))));

        verify(callMapper, never()).insert(any(WardCallEntity.class));
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("载荷契约不符：解析失败按消费失败处置（settleFailure 后重抛走死信）")
    void rejectsPayloadViolatingContract() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        // 类型错乱脏载荷（payload 为字符串而非对象——treeToValue 抛 MismatchedInputException）
        var brokenEnvelope = new com.fuyun.common.messaging.EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                "iot",
                WardMessagingConstants.EVENT_IOT_ALARM_TRIGGERED,
                "1",
                "it-trace",
                objectMapper.getNodeFactory().textNode("not-an-object"));
        Message broken;
        try {
            broken = new Message(
                    new EventEnvelopeCodec(objectMapper).toJson(brokenEnvelope).getBytes(StandardCharsets.UTF_8),
                    new MessageProperties());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> listener.onAlarmTriggered(broken)).isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).settleFailure(any(), any());
        verify(idempotencyService, never()).recordProcessed(any());
    }

    /** 告警触发载荷夹具（输液告急帧） */
    private static AlarmTriggeredPayload payload(String alarmNo, String triggerValue) {
        return new AlarmTriggeredPayload(
                alarmNo,
                "dev-pump-1",
                5L,
                null,
                1001L,
                "CRITICAL",
                INFUSION_SHORTAGE_METRIC_CODE,
                triggerValue,
                77L,
                OCCURRED_AT);
    }

    /** 信封构造（全局 ObjectMapper 序列化载荷进线格式） */
    private EventEnvelope envelope(AlarmTriggeredPayload payload) {
        return new EventEnvelope(
                EVENT_ID,
                OffsetDateTime.now().toInstant(),
                "iot",
                WardMessagingConstants.EVENT_IOT_ALARM_TRIGGERED,
                "1",
                "it-trace",
                objectMapper.valueToTree(payload));
    }

    /** 原始消息帧构造（UTF-8 信封线格式，AUTO 确认承接形态——codec 同源序列化） */
    private Message message(EventEnvelope envelope) {
        try {
            return new Message(
                    new EventEnvelopeCodec(objectMapper).toJson(envelope).getBytes(StandardCharsets.UTF_8),
                    new MessageProperties());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Captor
    private ArgumentCaptor<WardCallEntity> callCaptor;
}

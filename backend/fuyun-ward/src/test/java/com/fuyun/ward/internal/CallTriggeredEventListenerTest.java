package com.fuyun.ward.internal;

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
import com.fuyun.iot.api.payload.CallTriggeredPayload;
import com.fuyun.ward.cache.WardSeqGate;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
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
 * 设备呼叫触发消费监听器单测（P2 PR-2 Task 12 审查 Important-1 回接）：标准三段式接入、设备源
 * 呼叫落行（source=IOT/source_ref=触发引用申报语义）、幂等域 consumer_module=ward、载荷契约不符
 * 按消费失败处置（settleFailure 后重抛走死信——失败重投域语义）。
 */
@ExtendWith(MockitoExtension.class)
class CallTriggeredEventListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "f8c5b7d9-0ab4-4ec6-cf37-5c8b9dae0f34";

    /** 信封发生时刻：五要素登记断言基准（UTC 语义） */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-27T08:00:00Z");

    /** iot 侧触发引用夹具（联动回接路径=联动执行号，source_ref 对账锚） */
    private static final String TRIGGER_REF = "LG2026092700001";

    /** 测试载荷：病区呼叫设备触发（紧急呼叫） */
    private static final CallTriggeredPayload PAYLOAD =
            new CallTriggeredPayload(TRIGGER_REF, "dev-bedside-9", "EMERGENCY", 12L, 1001L, OCCURRED_AT);

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private WardCallMapper callMapper;

    @Mock
    private WardSeqGate seqGate;

    @Captor
    private ArgumentCaptor<ReceivedEventRecord> recordCaptor;

    @Captor
    private ArgumentCaptor<WardCallEntity> callCaptor;

    private CallTriggeredEventListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new CallTriggeredEventListener(
                new IdempotentConsumerSupport(
                        idempotencyService, new EventEnvelopeCodec(objectMapper), WardMessagingConstants.MODULE),
                objectMapper,
                callMapper,
                seqGate);
    }

    @Test
    @DisplayName("设备源呼叫落行：source=IOT、call_type=载荷词表回解析、source_ref=触发引用对账锚")
    void landsDeviceSourcedCallFromPayload() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);
        when(seqGate.nextCallNo()).thenReturn("CALL2026092700003");

        listener.onCallTriggered(message(PAYLOAD));

        verify(callMapper).insert(callCaptor.capture());
        WardCallEntity entity = callCaptor.getValue();
        assertThat(entity.getCallNo()).isEqualTo("CALL2026092700003");
        assertThat(entity.getWardId()).isEqualTo(1001L);
        assertThat(entity.getBedId()).isEqualTo(12L);
        assertThat(entity.getDeviceId()).isEqualTo("dev-bedside-9");
        assertThat(entity.getCallType()).isEqualTo(CallType.EMERGENCY);
        assertThat(entity.getSource()).isEqualTo(CallSource.IOT);
        assertThat(entity.getStatus()).isEqualTo(CallStatus.CREATED);
        assertThat(entity.getSourceRef())
                .as("source_ref=触发引用（联动执行号对账锚，语义申报见类注释）")
                .isEqualTo(TRIGGER_REF);
        verify(idempotencyService).recordProcessed(recordCaptor.capture());
        assertThat(recordCaptor.getValue().consumerModule()).isEqualTo(WardMessagingConstants.MODULE);
        assertThat(recordCaptor.getValue().eventType()).isEqualTo(WardMessagingConstants.EVENT_IOT_CALL_TRIGGERED);
    }

    @Test
    @DisplayName("重复投递跳过：tryAcquire 返回 false 直接返回（AUTO 确认），不触达落行与登记")
    void skipsRedeliveredMessageWithoutBusinessOrRecord() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(false);

        listener.onCallTriggered(message(PAYLOAD));

        verify(callMapper, never()).insert(any(WardCallEntity.class));
        verify(idempotencyService, never()).recordProcessed(any());
    }

    @Test
    @DisplayName("载荷契约不符：解析失败按消费失败处置（settleFailure 后重抛走死信——失败重投域语义）")
    void rejectsPayloadViolatingContract() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        // 类型错乱脏载荷（payload 为字符串而非对象——treeToValue 抛 MismatchedInputException）
        var brokenEnvelope = new EventEnvelope(
                EVENT_ID,
                OCCURRED_AT,
                "iot",
                WardMessagingConstants.EVENT_IOT_CALL_TRIGGERED,
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

        assertThatThrownBy(() -> listener.onCallTriggered(broken)).isInstanceOf(IllegalStateException.class);
        verify(idempotencyService).settleFailure(any(), any());
        verify(idempotencyService, never()).recordProcessed(any());
    }

    /** 原始消息帧构造（UTF-8 信封线格式，codec 同源序列化） */
    private Message message(CallTriggeredPayload payload) {
        var envelope = new EventEnvelope(
                EVENT_ID,
                OffsetDateTime.now().toInstant(),
                "iot",
                WardMessagingConstants.EVENT_IOT_CALL_TRIGGERED,
                "1",
                "it-trace",
                objectMapper.valueToTree(payload));
        try {
            return new Message(
                    new EventEnvelopeCodec(objectMapper).toJson(envelope).getBytes(StandardCharsets.UTF_8),
                    new MessageProperties());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

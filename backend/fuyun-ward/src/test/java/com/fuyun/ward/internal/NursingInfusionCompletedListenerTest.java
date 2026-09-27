package com.fuyun.ward.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.mapper.WardCallMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 拔针复位消费监听器单测（P2 PR-2 Task 12 Step 5 消费骨架）：nursing.infusion.completed
 * （V800 id 63）到达即按 patient_id 复位活跃输液呼叫行——载荷无设备锚，patient_id 为唯一复位键；
 * 患者为空的帧仅留痕不复位。nursing 发布端 PR-3 实装后生效（联调债申报）。
 */
@ExtendWith(MockitoExtension.class)
class NursingInfusionCompletedListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "d6a3f5b7-8e92-4ca4-ad15-3a6f7b8c9d12";

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private WardCallMapper callMapper;

    private NursingInfusionCompletedListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new NursingInfusionCompletedListener(
                new IdempotentConsumerSupport(
                        idempotencyService, new EventEnvelopeCodec(objectMapper), WardMessagingConstants.MODULE),
                callMapper);
    }

    @Test
    @DisplayName("拔针复位：载荷携带 patientId 时按患者复位活跃输液呼叫行（系统动作 CAS）")
    void resetsActiveInfusionCallsByPatient() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onInfusionCompleted(message(java.util.Map.of(
                "executionNo",
                "EX2026092600001",
                "patientId",
                8,
                "visitId",
                "20260901000001",
                "endedAt",
                "2026-09-26T07:00:00Z")));

        verify(callMapper).cancelActiveInfusionByPatient(8L);
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("患者为空的帧：仅留痕不复位（复位键缺位，PR-3 发布端契约确认）")
    void skipsResetWithoutPatientAnchor() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);

        listener.onInfusionCompleted(message(java.util.Map.of("executionNo", "EX2026092600002")));

        verify(callMapper, never()).cancelActiveInfusionByPatient(anyLong());
        verify(idempotencyService).recordProcessed(any());
    }

    /** 原始消息帧构造（载荷 JSON + UTF-8 信封线格式） */
    private Message message(java.util.Map<String, Object> payload) {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(payload);
        var envelope = new com.fuyun.common.messaging.EventEnvelope(
                EVENT_ID,
                OffsetDateTime.now().toInstant(),
                "nursing",
                WardMessagingConstants.EVENT_NURSING_INFUSION_COMPLETED,
                "1",
                null,
                node);
        try {
            return new Message(
                    new EventEnvelopeCodec(objectMapper).toJson(envelope).getBytes(StandardCharsets.UTF_8),
                    new MessageProperties());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

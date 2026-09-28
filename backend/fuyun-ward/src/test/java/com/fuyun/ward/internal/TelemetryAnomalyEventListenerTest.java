package com.fuyun.ward.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.vo.VitalAnomalyVO;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 体征采集质量注记消费监听器单测（P2 PR-2 Task 12 Step 5）：iot.telemetry.anomaly（V1004 id 78）
 * 到达即按 deviceId 落 Redis 注记快照（TTL 24h 自然过期），体征看板读时出注记——「消费 vs 查询面」
 * 取消费面（iot 无质量查询端口且禁跨模块读表，实测结论注记）。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryAnomalyEventListenerTest {

    /** 测试事件号：信封 eventId（UUID 形态，固定值保证幂等键断言确定性） */
    private static final String EVENT_ID = "e7b4a6c8-9fa3-4db5-be26-4b7a8c9dae23";

    /** 测试载荷：床垫 presence 指标断流 */
    private static final TelemetryAnomalyPayload PAYLOAD = new TelemetryAnomalyPayload(
            "dev-bed-mattress-1",
            "MDC_BED_PRESENCE",
            "STREAM_GAP",
            Instant.parse("2026-09-26T06:00:00Z"),
            Instant.parse("2026-09-26T07:00:00Z"));

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<String> valueCaptor;

    private TelemetryAnomalyEventListener listener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new TelemetryAnomalyEventListener(
                new IdempotentConsumerSupport(
                        idempotencyService, new EventEnvelopeCodec(objectMapper), WardMessagingConstants.MODULE),
                objectMapper,
                redisTemplate);
    }

    @Test
    @DisplayName("断流异常注记：按 deviceId 键写 Redis 快照（值=载荷 JSON，TTL 24h）并登记消费")
    void writesAnomalySnapshotByDeviceId() {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        listener.onTelemetryAnomaly(message(PAYLOAD));

        verify(valueOperations)
                .set(
                        eq(WardMessagingConstants.VITAL_ANOMALY_KEY_PREFIX + PAYLOAD.deviceId()),
                        anyString(),
                        eq(WardMessagingConstants.VITAL_ANOMALY_TTL));
        verify(idempotencyService).recordProcessed(any());
    }

    @Test
    @DisplayName("注记值可反解析回载荷契约（看板读取侧同源 JSON 形态的一致性锚）")
    void snapshotValueRoundTripsPayloadContract() throws Exception {
        when(idempotencyService.tryAcquire(EVENT_ID, WardMessagingConstants.MODULE))
                .thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        listener.onTelemetryAnomaly(message(PAYLOAD));

        verify(valueOperations).set(any(String.class), valueCaptor.capture(), any(Duration.class));
        // 反解析走看板读取同款形态（载荷 record → 注记 VO 转换的一致性锚）
        TelemetryAnomalyPayload parsed = objectMapper.readValue(valueCaptor.getValue(), TelemetryAnomalyPayload.class);
        VitalAnomalyVO vo = new VitalAnomalyVO(
                parsed.deviceId(),
                parsed.metricCode(),
                parsed.anomalyType(),
                parsed.lastOccurredAt() == null ? null : parsed.lastOccurredAt().atOffset(ZoneOffset.UTC),
                parsed.detectedAt().atOffset(ZoneOffset.UTC));
        assertThat(vo.deviceId()).isEqualTo("dev-bed-mattress-1");
        assertThat(vo.anomalyType()).isEqualTo("STREAM_GAP");
    }

    /** 原始消息帧构造（载荷 JSON + UTF-8 信封线格式，codec 同源序列化） */
    private Message message(TelemetryAnomalyPayload payload) {
        var envelope = new com.fuyun.common.messaging.EventEnvelope(
                EVENT_ID,
                OffsetDateTime.now().toInstant(),
                "iot",
                WardMessagingConstants.EVENT_IOT_TELEMETRY_ANOMALY,
                "1",
                null,
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

package com.fuyun.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 消费模板单测（终审 Minor「消费/缓存失效范式收敛」验收面）：三段式语义逐项锁定——
 * 重复跳过、成功登记、失败留痕且原异常上抛（不遮蔽，D-7/W-6③ 口径）。
 */
@ExtendWith(MockitoExtension.class)
class IdempotentConsumerSupportTest {

    @Mock
    private MessageIdempotencyService idempotencyService;

    @Mock
    private EventEnvelopeCodec codec;

    private static final String ENVELOPE_JSON = "{\"eventId\":\"e-1\"}";

    private Message raw() {
        return new Message(ENVELOPE_JSON.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private EventEnvelope envelope() {
        return new EventEnvelope(
                "e-1",
                Instant.now(),
                "billing",
                "billing.fee.created",
                "1",
                null,
                new ObjectMapper().createObjectNode().put("feeId", 1L));
    }

    @Test
    @DisplayName("重复投递：tryAcquire=false 直接跳过，业务与台账零交互")
    void duplicateDeliverySkipsBusiness() {
        when(codec.fromJson(ENVELOPE_JSON)).thenReturn(envelope());
        when(idempotencyService.tryAcquire("e-1", "billing")).thenReturn(false);
        AtomicInteger handled = new AtomicInteger();

        new IdempotentConsumerSupport(idempotencyService, codec, "billing")
                .consume(raw(), e -> handled.incrementAndGet());

        assertThat(handled.get()).isZero();
        verify(idempotencyService, never()).recordProcessed(any());
    }

    @Test
    @DisplayName("业务成功：handler 收到解析后信封并登记 PROCESSED")
    void successPathRecordsProcessed() {
        EventEnvelope env = envelope();
        when(codec.fromJson(ENVELOPE_JSON)).thenReturn(env);
        when(idempotencyService.tryAcquire("e-1", "billing")).thenReturn(true);

        new IdempotentConsumerSupport(idempotencyService, codec, "billing").consume(raw(), e -> {});

        verify(idempotencyService)
                .recordProcessed(
                        new ReceivedEventRecord("e-1", "billing.fee.created", "billing", env.occurredAt(), "billing"));
    }

    @Test
    @DisplayName("业务异常：FAILED 留痕 + 原异常上抛（交容器有界重试，禁吞禁遮蔽）")
    void businessFailureSettlesFailureAndRethrows() {
        when(codec.fromJson(ENVELOPE_JSON)).thenReturn(envelope());
        when(idempotencyService.tryAcquire(anyString(), anyString())).thenReturn(true);
        IllegalStateException boom = new IllegalStateException("业务失败");
        doThrow(boom).when(idempotencyService).recordProcessed(any());

        assertThatThrownBy(() ->
                        new IdempotentConsumerSupport(idempotencyService, codec, "billing").consume(raw(), e -> {}))
                .isSameAs(boom);
        verify(idempotencyService).settleFailure(any(ReceivedEventRecord.class), any(RuntimeException.class));
    }
}

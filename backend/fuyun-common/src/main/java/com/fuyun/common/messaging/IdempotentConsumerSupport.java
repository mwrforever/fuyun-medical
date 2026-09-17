package com.fuyun.common.messaging;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;

/**
 * 标准幂等消费模板（终审 Minor「消费/缓存失效范式收敛」基类）：MessageIdempotencyService
 * javadoc 三段式范式的单一实现——NX 抢占（D-7 回查）→ 业务 → PROCESSED 登记；
 * 异常 FAILED 留痕并原样上抛（settleFailure 内部 addSuppressed 双保留，W-6③ 口径）。
 *
 * <p>使用姿势：各消费者 @RabbitListener 方法体一行委托本模板，业务处理以 handler 传入
 * （raw Message 经 EventEnvelopeCodec 解析后交付）；AUTO 确认语义不变——handler/登记抛异常
 * 即由容器有界重试、耗尽进 fy.dlx。
 *
 * <p>线程安全：无状态，Spring singleton 安全。
 */
@Slf4j
public class IdempotentConsumerSupport {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    /** 消费者模块域标识（幂等键第二要素 + received_event 台账列） */
    private final String consumerModule;

    /**
     * 全参构造器。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @param consumerModule     消费方模块标识，非空（如 billing/patient）
     */
    public IdempotentConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec, String consumerModule) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.consumerModule = consumerModule;
    }

    /**
     * 标准三段式消费入口。
     *
     * @param message 原始消息帧，非空（UTF-8 JSON 信封）
     * @param handler 业务处理（接收已解析信封），非空；抛 RuntimeException 即失败留痕后上抛
     * @throws IllegalStateException 信封解析失败（不合规帧）时触发——交由容器拒收，死信留痕
     */
    public void consume(Message message, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接跳过即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), consumerModule)) {
            return;
        }
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(), envelope.eventType(), envelope.producer(), envelope.occurredAt(), consumerModule);
        try {
            handler.accept(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（不遮蔽 e），上抛交容器有界重试耗尽进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }
}

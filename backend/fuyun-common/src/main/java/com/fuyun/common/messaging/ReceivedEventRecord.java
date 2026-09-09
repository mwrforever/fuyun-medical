package com.fuyun.common.messaging;

import java.time.Instant;

/**
 * 消费成功登记记录（received_event 台账的契约参数对象）：承载信封五要素，供
 * {@link MessageIdempotencyService#recordProcessed(ReceivedEventRecord)} 落库使用。
 *
 * <p>record 纯数据载体（backend 宪法 A.1-2 透明浅不可变；A.7-1 参数对象化），
 * equals/hashCode 直接采用 record 语义。
 *
 * @param eventId        事件信封 eventId（UUID 字符串），非空；来源：消费消息解析出的信封
 * @param eventType      事件类型 {@code <模块>.<实体>.<动作>}，非空；来源：消费消息解析出的信封
 * @param producer       生产模块域标识，非空；来源：消费消息解析出的信封
 * @param occurredAt     事件发生时刻（信封字段，UTC 语义），非空；来源：消费消息解析出的信封
 * @param consumerModule 消费者模块域标识，非空；幂等键第二要素，来源：消费方自身模块配置
 */
public record ReceivedEventRecord(
        String eventId, String eventType, String producer, Instant occurredAt, String consumerModule) {}

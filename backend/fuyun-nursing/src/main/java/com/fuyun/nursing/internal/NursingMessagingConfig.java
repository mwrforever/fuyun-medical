package com.fuyun.nursing.internal;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 消息装配：发布/消费模板 Bean 与发布器注册集中点（OutpatientMessagingConfig 同款形态；
 * 本类归 internal/，由 fuyun-app NursingConfig @Import 生效——装配根豁免 Modulith 边界，
 * IotConfig 引 iot/internal 先例）。交换机全集归 integration 禁私建（A.5-4）；
 * 订阅队列声明（Declarables）随消费任务逐批追加（先登记后订阅红线），P1 当前零订阅故无队列 Bean；
 * 发布面经 NursingEventPublisher 于业务事务提交后出 MQ（Task 5/7/8/9 逐批接线）。
 */
@Configuration
@Import({NursingEventPublisher.class})
public class NursingMessagingConfig {

    /**
     * 护理域发送模板 Bean（GC7 多实例 @Qualifier 定绑锚：NursingEventPublisher 构造器按名取用；
     * 单槽位回调红线见 DomainEventSender javadoc / GC8 不注册回调）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean("nursingEventSender")
    public DomainEventSender nursingEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, NursingMessagingConstants.MODULE);
    }

    /**
     * 护理域消费模板 Bean（GC7 多实例 @Qualifier 定绑锚：后续消费任务按名取用；
     * 标准三段式单一实现，消费者模块标识=nursing）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean("nursingConsumerSupport")
    public IdempotentConsumerSupport nursingConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, NursingMessagingConstants.MODULE);
    }
}

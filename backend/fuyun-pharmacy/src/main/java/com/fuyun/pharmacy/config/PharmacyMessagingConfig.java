package com.fuyun.pharmacy.config;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.internal.PharmacyBillingSyncListener;
import com.fuyun.pharmacy.internal.PharmacyChargedOrderListener;
import com.fuyun.pharmacy.internal.PharmacyEventPublisher;
import java.util.Arrays;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M06 消息装配：发布/消费模板 Bean、订阅队列治理声明、发布器/监听器注册集中点
 * （BillingMessagingConfig 同款形态；本类由 fuyun-app PharmacyConfig @Import 生效，
 * 交换机全集归 integration 禁私建 A.5-4）。SUBSCRIBED_EVENT_TYPES 随消费任务逐批追加，
 * 监听器类同步追加进 @Import（先登记后订阅红线）。
 */
@Configuration
@Import({PharmacyEventPublisher.class, PharmacyChargedOrderListener.class, PharmacyBillingSyncListener.class})
public class PharmacyMessagingConfig {

    /**
     * 药事域发送模板 Bean（单槽位回调红线见 DomainEventSender javadoc / TASK.md W-11）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean
    public DomainEventSender pharmacyEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, PharmacyMessagingConstants.MODULE);
    }

    /**
     * 药事域消费模板 Bean（标准三段式单一实现，消费者模块标识=pharmacy）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean
    public IdempotentConsumerSupport pharmacyConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, PharmacyMessagingConstants.MODULE);
    }

    /**
     * 声明订阅队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V702 id 25–31 先行）。
     * Task 4 交付时全集为空数组（零声明平凡通过），消费任务逐批生效。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables pharmacyConsumerQueues(MessagingGovernance governance) {
        return new Declarables(Arrays.stream(PharmacyMessagingConstants.SUBSCRIBED_EVENT_TYPES)
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(PharmacyMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }
}

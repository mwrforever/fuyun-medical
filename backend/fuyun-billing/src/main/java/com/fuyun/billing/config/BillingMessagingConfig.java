package com.fuyun.billing.config;

import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.internal.BillingChargeEventListener;
import com.fuyun.billing.internal.BillingEventPublisher;
import com.fuyun.billing.internal.BillingPharmacyOccupyListener;
import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import java.util.Arrays;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M13 消息装配：发布/消费模板 Bean（Task 5 common 基类的 billing 接线点，终审 Minor 收口另一半）、
 * CF-5 订阅两队列治理声明、发布器/监听器注册集中点（PatientMessagingConfig 同款形态；
 * 本类由 fuyun-app BillingConfig @Import 生效，交换机全集归 integration 禁私建 A.5-4）。
 */
@Configuration
@Import({BillingEventPublisher.class, BillingChargeEventListener.class, BillingPharmacyOccupyListener.class})
public class BillingMessagingConfig {

    /**
     * 收费域发送模板 Bean（单槽位回调红线见 DomainEventSender javadoc / TASK.md W-11）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean
    public DomainEventSender billingEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, BillingMessagingConstants.MODULE);
    }

    /**
     * 收费域消费模板 Bean（标准三段式单一实现，消费者模块标识=billing）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean
    public IdempotentConsumerSupport billingConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, BillingMessagingConstants.MODULE);
    }

    /**
     * 声明 CF-5 占位订阅两队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V605 id 23–24 先行）。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables billingConsumerQueues(MessagingGovernance governance) {
        return new Declarables(Arrays.stream(BillingMessagingConstants.SUBSCRIBED_EVENT_TYPES)
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(BillingMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }
}

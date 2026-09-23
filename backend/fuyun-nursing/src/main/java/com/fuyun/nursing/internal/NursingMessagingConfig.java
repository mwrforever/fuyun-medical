package com.fuyun.nursing.internal;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.util.Arrays;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 消息装配：发布/消费模板 Bean、订阅队列治理声明、发布器/监听器注册集中点
 * （OutpatientMessagingConfig 同款形态；本类归 internal/，由 fuyun-app NursingConfig @Import
 * 生效——装配根豁免 Modulith 边界，IotConfig 引 iot/internal 先例）。交换机全集归 integration
 * 禁私建（A.5-4）；订阅队列声明随消费任务逐批追加（先登记后订阅红线，Task 3 起三订阅），
 * 监听器类同步追加进 @Import；发布面经 NursingEventPublisher 于业务事务提交后出 MQ。
 */
@Configuration
@Import({
    NursingEventPublisher.class,
    PatientHealthSummaryListener.class,
    PatientMergedListener.class,
    PatientSplitListener.class
})
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

    /**
     * 声明订阅队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V105 id 11/12/16 既有登记）。
     * Task 3 交付三条：健康档案变更/患者合并/患者拆分（成对口径 M-25）。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables nursingConsumerQueues(MessagingGovernance governance) {
        return new Declarables(Arrays.stream(NursingMessagingConstants.SUBSCRIBED_EVENT_TYPES)
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(NursingMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }
}

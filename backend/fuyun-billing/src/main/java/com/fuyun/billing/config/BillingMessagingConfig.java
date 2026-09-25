package com.fuyun.billing.config;

import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.internal.BillingChargeEventListener;
import com.fuyun.billing.internal.BillingEventPublisher;
import com.fuyun.billing.internal.BillingInpatientEventListener;
import com.fuyun.billing.internal.BillingPharmacyOccupyListener;
import com.fuyun.billing.internal.InpatientDailyChargeJob;
import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import java.util.Arrays;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M13 消息装配：发布/消费模板 Bean（Task 5 common 基类的 billing 接线点，终审 Minor 收口另一半）、
 * CF-5 订阅两队列治理声明、发布器/监听器注册集中点（PatientMessagingConfig 同款形态；
 * 本类由 fuyun-app BillingConfig @Import 生效，交换机全集归 integration 禁私建 A.5-4）。
 * P2 PR-1 Task 13 追加：住院六事件通配消费面（inpatient.visit.#/inpatient.order.# 自声明绑定）
 * 与床位费日切任务注册（OrderPlanDecomposeJob 经 InpatientMessagingConfig 注册先例）。
 */
@Configuration
@Import({
    BillingEventPublisher.class,
    BillingChargeEventListener.class,
    BillingPharmacyOccupyListener.class,
    BillingInpatientEventListener.class,
    InpatientDailyChargeJob.class
})
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

    /**
     * 住院六事件通配消费队列自声明（q.billing.inpatient.visit.# 与 q.billing.inpatient.order.#
     * 通配绑定 fy.topic）：governance 订阅登记按登记名精确匹配且命名审查拒绝 # 通配段
     * （QueueGovernorImpl EVENT_TYPE_PATTERN/registerSubscriber），无法承载通配绑定——故按治理
     * 同款形态自声明（Task 12 drug 子键队列自声明同款先例：durable quorum + 死信指向 fy.dlx +
     * 绑定主交换机；非私建交换机，队列/绑定交 RabbitAdmin 幂等声明）。六事件登记面归
     * V800/V901 既有行（先登记后订阅红线满足）。
     *
     * @return 声明集合（两队列 + 两通配绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables billingInpatientWildcardQueues() {
        Queue visitQueue = selfDeclaredWildcardQueue(BillingMessagingConstants.BINDING_KEY_INPATIENT_VISIT_ALL);
        Queue orderQueue = selfDeclaredWildcardQueue(BillingMessagingConstants.BINDING_KEY_INPATIENT_ORDER_ALL);
        return new Declarables(
                visitQueue,
                orderQueue,
                wildcardBinding(visitQueue, BillingMessagingConstants.BINDING_KEY_INPATIENT_VISIT_ALL),
                wildcardBinding(orderQueue, BillingMessagingConstants.BINDING_KEY_INPATIENT_ORDER_ALL));
    }

    /**
     * 通配消费队列构造（治理同款形态：durable quorum + fy.dlx 死信；队列名=q.模块.绑定键，
     * Task 12 drug 队列命名同款推导）。
     *
     * @param bindingKey 通配绑定键（inpatient.visit.# / inpatient.order.#），非空
     * @return 队列，非空
     */
    private static Queue selfDeclaredWildcardQueue(String bindingKey) {
        return QueueBuilder.durable(BillingMessagingConstants.CONSUMER_QUEUE_PREFIX
                        + BillingMessagingConstants.MODULE
                        + "."
                        + bindingKey)
                .quorum()
                .deadLetterExchange(BillingMessagingConstants.DLX_EXCHANGE)
                .build();
    }

    /**
     * 通配绑定构造（主交换机 fy.topic，路由键=通配绑定键）。
     *
     * @param queue      目标队列，非空
     * @param bindingKey 通配绑定键，非空
     * @return 绑定，非空
     */
    private static Binding wildcardBinding(Queue queue, String bindingKey) {
        return new Binding(
                queue.getName(),
                Binding.DestinationType.QUEUE,
                BillingMessagingConstants.TOPIC_EXCHANGE,
                bindingKey,
                null);
    }
}

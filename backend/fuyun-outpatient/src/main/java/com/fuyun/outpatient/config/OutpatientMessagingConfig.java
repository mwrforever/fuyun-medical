package com.fuyun.outpatient.config;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.DelayQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.internal.AppointmentTimeoutTickListener;
import com.fuyun.outpatient.internal.AppointmentTimeoutTickSeeder;
import com.fuyun.outpatient.internal.AppointmentTimeoutTickSender;
import com.fuyun.outpatient.internal.DelayEnvelopeSender;
import com.fuyun.outpatient.internal.OutpatientAppointmentTimeoutListener;
import com.fuyun.outpatient.internal.OutpatientDispenseCompletedListener;
import com.fuyun.outpatient.internal.OutpatientDispenseReturnedListener;
import com.fuyun.outpatient.internal.OutpatientEventPublisher;
import com.fuyun.outpatient.internal.OutpatientFeeCreatedListener;
import com.fuyun.outpatient.internal.OutpatientPrescriptionCancelledListener;
import com.fuyun.outpatient.internal.OutpatientRefundApprovedListener;
import com.fuyun.outpatient.internal.OutpatientSettlementCompletedListener;
import com.fuyun.outpatient.properties.OutpatientProperties;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 消息装配：发布/消费模板 Bean、订阅队列治理声明、延迟档位声明、发布器注册集中点
 * （PharmacyMessagingConfig 同款形态；本类由 fuyun-app OutpatientConfig @Import 生效，
 * 交换机全集归 integration 禁私建 A.5-4）。SUBSCRIBED_EVENT_TYPES 随消费任务逐批追加，
 * 监听器类同步追加进 @Import（先登记后订阅红线）。P2 PR-4E Task 8 追加（W-27）：号源超时
 * tick 消费链三件（AppointmentTimeoutTickListener/AppointmentTimeoutTickSender/
 * AppointmentTimeoutTickSeeder）+ tick 档位与消费队列两 Bean（自声明豁免口径见
 * OutpatientMessagingConstants 类注释）。
 */
@Configuration
@Import({
    OutpatientEventPublisher.class,
    DelayEnvelopeSender.class,
    OutpatientAppointmentTimeoutListener.class,
    OutpatientRefundApprovedListener.class,
    OutpatientFeeCreatedListener.class,
    OutpatientSettlementCompletedListener.class,
    OutpatientPrescriptionCancelledListener.class,
    OutpatientDispenseCompletedListener.class,
    OutpatientDispenseReturnedListener.class,
    AppointmentTimeoutTickListener.class,
    AppointmentTimeoutTickSender.class,
    AppointmentTimeoutTickSeeder.class
})
@EnableConfigurationProperties(OutpatientProperties.class)
public class OutpatientMessagingConfig {

    /**
     * 门诊域发送模板 Bean（单槽位回调红线见 DomainEventSender javadoc / TASK.md W-11）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean
    public DomainEventSender outpatientEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, OutpatientMessagingConstants.MODULE);
    }

    /**
     * 门诊域消费模板 Bean（标准三段式单一实现，消费者模块标识=outpatient）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean
    public IdempotentConsumerSupport outpatientConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, OutpatientMessagingConstants.MODULE);
    }

    /**
     * 声明订阅队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V204 id 32–40 先行）。
     * Task 3 交付时全集为空数组（零声明平凡通过），消费任务逐批生效。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables outpatientConsumerQueues(MessagingGovernance governance) {
        return new Declarables(Arrays.stream(OutpatientMessagingConstants.SUBSCRIBED_EVENT_TYPES)
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(OutpatientMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }

    /**
     * 预约支付超时延迟档位（Spec §8 M20：delay.appointment-timeout；单档位、TTL=支付时限）：
     * 到期经 DLX 以 outpatient.appointment.timeout 路由键回 fy.topic，由本模块 timeout 监听器消费。
     * 声明幂等（RabbitAdmin）；TTL 与 OutpatientProperties.appointmentTimeout() 同源（Task 5 接线）。
     *
     * @param governance 消息治理构件，非空
     * @param properties 门诊域参数，非空
     * @return 声明集合（延迟队列 + 绑定）
     */
    @Bean
    public Declarables appointmentTimeoutDelayQueue(MessagingGovernance governance, OutpatientProperties properties) {
        return governance.declareDelayQueue(new DelayQueueSpec(
                "appointment-timeout",
                properties.appointmentTimeout(),
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT));
    }

    /**
     * 号源超时 tick 延迟档位（W-27，P2 PR-4E Task 8：delay.appointment-timeout-tick；单档位、
     * TTL=60 秒档位声明冻结）：到期经 DLX 以 outpatient.appointment-timeout.tick 路由键回
     * fy.topic，由 tick 监听器消费驱动惰性扫描释放（与既有 15m 档 appointment-timeout 并存
     * ——A.5-7 单档位语义，两档位各司其职：15m 档携载荷信封逐单到期、tick 档空帧 60s 心跳
     * 扫描兜底）。tick 键非事件不入 event_registry（先登记后订阅红线豁免口径见
     * OutpatientMessagingConstants 类注释）；declareDelayQueue 无先登记校验（A.5-7 延迟档位
     * 语义——QueueGovernorImpl 实测：仅命名与 TTL 参数校验，不触 event_registry）；
     * 声明幂等（RabbitAdmin）。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（延迟队列 + 绑定）
     */
    @Bean
    public Declarables appointmentTimeoutTickDelayQueue(MessagingGovernance governance) {
        return governance.declareDelayQueue(new DelayQueueSpec(
                OutpatientMessagingConstants.DELAY_BUSINESS_APPOINTMENT_TIMEOUT_TICK,
                Duration.ofSeconds(60),
                OutpatientMessagingConstants.ROUTING_APPOINTMENT_TIMEOUT_TICK));
    }

    /**
     * 号源超时 tick 消费队列自声明（W-27——tick 键无 event_registry 登记面，
     * QueueGovernorImpl.declareConsumerQueue 会经 registerSubscriber 对未登记键抛
     * IllegalStateException 阻断启动，故治理构件无可用声明入口；nursing taskOverdueTickQueue
     * 同族先例——W-27 另一同族 outpatient.appointment.timeout 为 V204 id 39 在册事件走
     * declareConsumerQueue，不适用本键）。自声明姿态逐字镜像 QueueGovernorImpl 消费队列形态：
     * durable + 显式 quorum + 死信指向 fy.dlx 且不设死信路由键（死信保留原始路由键可溯源）+
     * fy.topic 绑定（key=tick 路由键）；RabbitAdmin 幂等声明。
     *
     * @return 声明集合（tick 消费队列 + 绑定）
     */
    @Bean
    public Declarables appointmentTimeoutTickQueue() {
        Queue tickQueue = QueueBuilder.durable(OutpatientMessagingConstants.QUEUE_APPOINTMENT_TIMEOUT_TICK)
                .quorum()
                .deadLetterExchange(MessagingConstants.EXCHANGE_DLX)
                .build();
        Binding tickBinding = new Binding(
                OutpatientMessagingConstants.QUEUE_APPOINTMENT_TIMEOUT_TICK,
                Binding.DestinationType.QUEUE,
                MessagingConstants.EXCHANGE_TOPIC,
                OutpatientMessagingConstants.ROUTING_APPOINTMENT_TIMEOUT_TICK,
                null);
        return new Declarables(tickQueue, tickBinding);
    }
}

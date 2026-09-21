package com.fuyun.outpatient.config;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.DelayQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
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
import java.util.Arrays;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 消息装配：发布/消费模板 Bean、订阅队列治理声明、延迟档位声明、发布器注册集中点
 * （PharmacyMessagingConfig 同款形态；本类由 fuyun-app OutpatientConfig @Import 生效，
 * 交换机全集归 integration 禁私建 A.5-4）。SUBSCRIBED_EVENT_TYPES 随消费任务逐批追加，
 * 监听器类同步追加进 @Import（先登记后订阅红线）。
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
    OutpatientDispenseReturnedListener.class
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
}

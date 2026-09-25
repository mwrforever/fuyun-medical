package com.fuyun.inpatient.internal;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import java.util.Arrays;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M04 消息装配：发布/消费模板 Bean、订阅队列治理声明、发布器注册集中点
 * （NursingMessagingConfig 同款形态；本类归 internal/，经 fuyun-app InpatientConfig
 * @Import 启用——启用动作归 Task 3，与 InpatientWebConfig 一并挂接）。交换机全集归 integration
 * 禁私建（A.5-4）；订阅队列声明随消费任务逐批追加（先登记后订阅红线，本批五订阅：
 * M06 审方回执两条 + M13 计费联动三条，均 fy.topic 精确键绑定，fy.delay 声明不涉及）；
 * 发布面经 InpatientEventPublisher 于业务事务提交后出 MQ。
 */
@Configuration
@Import(InpatientEventPublisher.class)
public class InpatientMessagingConfig {

    /**
     * 住院域发送模板 Bean（GC7 多实例 @Qualifier 定绑锚：InpatientEventPublisher 构造器按名取用；
     * 单槽位回调红线见 DomainEventSender javadoc / GC8 不注册回调）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean("inpatientEventSender")
    public DomainEventSender inpatientEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, InpatientMessagingConstants.MODULE);
    }

    /**
     * 住院域消费模板 Bean（GC7 多实例 @Qualifier 定绑锚：后续消费任务按名取用；
     * 标准三段式单一实现，消费者模块标识=inpatient）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean("inpatientConsumerSupport")
    public IdempotentConsumerSupport inpatientConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, InpatientMessagingConstants.MODULE);
    }

    /**
     * 声明订阅队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V800 id 53/54、
     * V605 id 19/21、V1002 id 73 既有登记——billing.arrears.approved 随 billing 侧 V1002 落地，
     * 先登记后订阅红线保证启用时序）。本批五条：M06 审方回执两条 + M13 计费联动三条。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 精确键绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables inpatientConsumerQueues(MessagingGovernance governance) {
        return new Declarables(Arrays.stream(InpatientMessagingConstants.SUBSCRIBED_EVENT_TYPES)
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(InpatientMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }
}

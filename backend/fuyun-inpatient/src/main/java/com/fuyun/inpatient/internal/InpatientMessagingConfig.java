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
 * M04 消息装配：发布/消费模板 Bean、订阅队列治理声明、发布器与消费监听器注册集中点
 * （NursingMessagingConfig 同款形态；本类归 internal/，经 fuyun-app InpatientConfig
 * @Import 启用）。交换机全集归 integration 禁私建（A.5-4）；订阅队列五条声明已随 Task 2
 * 落位（SUBSCRIBED_EVENT_TYPES：M06 审方回执两条 + M13 计费联动三条，均 fy.topic 精确键
 * 绑定，fy.delay 声明不涉及）；Task 6 追加审方回执消费监听器 @Import（V800 id 53/54 两队列
 * 的首个消费者——队列声明零新增，绑定复用既有声明）；Task 8 追加日切分解定时任务 @Import
 * （PatientDuplicateScanJob 经 PatientMessagingConfig 注册先例——调度总开关归 fuyun-app
 * SchedulingConfig，本处零新配置面）；发布面经 InpatientEventPublisher 于业务事务提交后出 MQ。
 * Task 9 追加 M13 计费联动消费监听器 @Import（BillingEventListener——settlement.completed/
 * arrears.approved 两回执的出院放行业务体 + deposit.changed 队列承载面[消费逻辑归 Task 10]；
 * 三队列声明复用 Task 2 既有 SUBSCRIBED_EVENT_TYPES 声明，绑定零新增）。
 * Task 10 补全 deposit.changed 消费业务体（押金变动→欠费标识刷新，委托 AdmissionService——
 * 构造追加 AdmissionService 依赖，队列声明面仍零新增）。
 */
@Configuration
@Import({
    InpatientEventPublisher.class,
    PharmacyAuditReplyListener.class,
    OrderPlanDecomposeJob.class,
    BillingEventListener.class
})
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

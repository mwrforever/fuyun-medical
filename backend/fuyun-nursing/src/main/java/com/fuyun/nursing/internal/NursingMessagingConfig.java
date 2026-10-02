package com.fuyun.nursing.internal;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.DelayQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.time.Duration;
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
 * P2 PR-3 Task 4 追加：inpatient 医嘱事件族（order.transferred/order-plan.generated/
 * stopped/cancelled 四路合一）与就诊事件族（visit.admitted/transferred/discharge-requested/
 * discharged/bed.changed 五路）两监听器。 P2 PR-3 Task 5 追加：摆药签收衔接监听器
 * （DispenseSignoffListener——pharmacy.dispense.completed 首消费者，队列声明随既有
 * SUBSCRIBED_EVENT_TYPES id 28 项）与回签补偿扫描组件（ExecutionConfirmCompensator——
 * tick 接线归 Task 9，本任务仅注册 Bean 供调用）。P2 PR-3 Task 6 追加：IoT 告警执行单
 * 挂接监听器（IotAlarmExecutionListener——iot.alarm.triggered/escalated/closed 三路，
 * 队列声明随既有 SUBSCRIBED_EVENT_TYPES id 74–76 项）。
 */
@Configuration
@Import({
    NursingEventPublisher.class,
    PatientHealthSummaryListener.class,
    PatientMergedListener.class,
    PatientSplitListener.class,
    InpatientOrderEventListener.class,
    InpatientVisitEventListener.class,
    DispenseSignoffListener.class,
    ExecutionConfirmCompensator.class,
    IotAlarmExecutionListener.class
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
     * 声明订阅队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V105 id 11/12/16 与 V800/V702/V1004
     * 登记面既有）。P1 三条：健康档案变更/患者合并/患者拆分（成对口径 M-25）；P2 PR-3 扩十三条：
     * inpatient 医嘱/就诊/床位九条（Task 4/7 消费面）+ pharmacy 摆药签收一条（Task 6）+ iot 告警三条（Task 6），
     * 队列名由治理构件按 q.nursing.&lt;eventType&gt; 统一推导（契约锚 NursingEventContractTest）。
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

    /**
     * 任务逾期延迟档位（05-nursing Spec §4 / V805 头注：delay.task-overdue；单档位、TTL=60 秒）：
     * 到期经 DLX 以 nursing.task-overdue.tick 路由键回 fy.topic，由 Task 9 tick 监听器消费驱动
     * 逾期扫描与升级广播（nursing.task.overdue）。tick 键非事件不入 event_registry（先登记后订阅
     * 红线豁免口径见 NursingMessagingConstants 类注释）；声明幂等（RabbitAdmin）。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（延迟队列 + 绑定）
     */
    @Bean
    public Declarables taskOverdueDelayQueue(MessagingGovernance governance) {
        return governance.declareDelayQueue(new DelayQueueSpec(
                "task-overdue", Duration.ofSeconds(60), NursingMessagingConstants.ROUTING_TASK_OVERDUE_TICK));
    }
}

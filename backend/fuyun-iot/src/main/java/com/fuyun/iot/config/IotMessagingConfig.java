package com.fuyun.iot.config;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.internal.IotDomainPublisher;
import com.fuyun.iot.internal.IotEventPublisher;
import com.fuyun.iot.internal.IotFanoutListener;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * IoT 模块消息装配：发布/消费模板 Bean、消费队列治理声明与发布器/消费者 Bean 注册集中点
 * （B4.3 骨架 + P2 PR-2 Task 2 CF-7 增量；InpatientMessagingConfig 同款形态）。
 *
 * <p>com.fuyun.iot 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app IotConfig @Import 生效（不放宽扫描）。队列声明走 MessagingGovernance 构件（先登记
 * 后订阅：事件已在 V403/V1004 种子登记）；交换机全集仍由 integration MessagingGovernanceConfig
 * 声明，本配置不重复（禁私建交换机 A.5-4）。
 *
 * <p>P2 PR-2 Task 2 增量：iotEventSender/iotConsumerSupport 模板 Bean（GC7 跨模块多实例
 * @Qualifier 定绑锚）与 IotDomainPublisher 注册——CF-7 八事件（V1004 id 74–81）经
 * 「事务内 publishEvent → AFTER_COMMIT → iotEventSender fy.topic 直发」出 MQ（照住院域形态；
 * 事务内禁 MQ 发送红线），发布器不注册 Confirm/Returns 回调（GC8）；P0 设备状态自事件队列声明
 * 与既有扇出链（IotEventPublisher/IotFanoutListener）零改动。新事件的消费队列归消费方模块
 * （M05/M16）按先登记后订阅红线自行声明，本配置不代声明。
 *
 * <p>本配置与 IotAmqpConfig（@ConditionalOnProperty enabled 开关）解耦：模板 Bean/发布器/队列
 * 声明无条件生效——MQ 事件总线域不依赖 IoTDA AMQP 消费链开关；状态事件的产生源头（AMQP 状态
 * 帧消费）在 enabled=false 时不运行，扇出链路自然静默，零孤儿 Bean。
 */
@Configuration
@Import({IotEventPublisher.class, IotDomainPublisher.class, IotFanoutListener.class})
public class IotMessagingConfig {

    /**
     * IoT 域发送模板 Bean（GC7 多实例 @Qualifier 定绑锚：IotDomainPublisher 构造器按名取用；
     * 单槽位回调红线见 DomainEventSender javadoc / GC8 不注册回调）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean("iotEventSender")
    public DomainEventSender iotEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, IotMessagingConstants.MODULE);
    }

    /**
     * IoT 域消费模板 Bean（GC7 多实例 @Qualifier 定绑锚：后续消费任务按名取用；
     * 标准三段式单一实现，消费者模块标识=iot）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean("iotConsumerSupport")
    public IdempotentConsumerSupport iotConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, IotMessagingConstants.MODULE);
    }

    /**
     * 声明 iot 模块的设备状态自事件消费队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables deviceStatusConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(IotMessagingConstants.MODULE, IotMessagingConstants.EVENT_DEVICE_STATUS));
    }

    /**
     * 声明 iot 模块的告警触发自事件消费队列并绑定 fy.topic（P2 PR-2 Task 9 联动触发源主入口，
     * 事件 V1004 id 74 已登记；q.iot.iot.alarm.triggered，消费者 IotAlarmEventListener）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables alarmTriggeredConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(IotMessagingConstants.MODULE, IotMessagingConstants.EVENT_ALARM_TRIGGERED));
    }

    /**
     * 声明 iot 模块的告警关闭自事件消费队列并绑定 fy.topic（P2 PR-2 Task 11 扇出扩订阅，事件
     * V1004 id 76 已登记；q.iot.iot.alarm.closed，消费者 IotFanoutListener——大屏摘要变更触发源）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables alarmClosedConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(IotMessagingConstants.MODULE, IotMessagingConstants.EVENT_ALARM_CLOSED));
    }

    /**
     * 声明 iot 模块扇出链的告警触发自事件消费队列并绑定 fy.topic（P2 PR-2 Task 11 R1 修复，事件
     * V1004 id 74 已登记；q.iot-fanout.iot.alarm.triggered，消费者 IotFanoutListener——大屏摘要
     * 变更触发源）。「每消费者一队列」形态（同路由键多队列绑定）：联动链队列
     * q.iot.iot.alarm.triggered（IotAlarmEventListener）与本扇出队列各自独立消费互不竞争；
     * 消费者域标识 iot-fanout（{@link IotMessagingConstants#FANOUT_CONSUMER_MODULE}）同时派生
     * 独立幂等域，防联动链 PROCESSED 行经回查抑制本链消费。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables alarmTriggeredFanoutConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(new ConsumerQueueSpec(
                IotMessagingConstants.FANOUT_CONSUMER_MODULE, IotMessagingConstants.EVENT_ALARM_TRIGGERED));
    }
}

package com.fuyun.ward.internal;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.ward.constants.WardMessagingConstants;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 病房模块消息装配：发布/消费模板 Bean、消费队列治理声明集中点（IotMessagingConfig 同款形态）。
 *
 * <p>com.fuyun.ward 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app WardConfig @Import 生效（不放宽扫描）。队列声明走 MessagingGovernance 构件（先登记
 * 后订阅：三个订阅事件已分别在 V1004 id 74/78 与 V800 id 63 种子登记）；交换机全集仍由
 * integration MessagingGovernanceConfig 声明，本配置不重复（禁私建交换机 A.5-4）。
 *
 * <p>GC7 跨模块多实例定绑锚：wardEventSender/wardConsumerSupport 两模板 Bean（common 基类
 * 跨模块多实例，fuyun-app 上下文多候选必须 @Qualifier 按名定绑，禁赌回退链）；发布器
 * {@link WardEventPublisher} 注册于本配置 @Import。ward 侧四消费者（输液告急落行/设备源呼叫
 * 落行/拔针复位/体征质量注记）消费事件各异，「每消费者一队列」形态落四队列（q.ward.*），幂等域
 * 统一 consumer_module=ward（同事件仅单队列消费，无 iot-fanout 类跨域拆分需求）。
 */
@Configuration
@Import(WardEventPublisher.class)
public class WardMessagingConfig {

    /**
     * 病房域发送模板 Bean（GC7 多实例 @Qualifier 定绑锚：WardEventPublisher 构造器按名取用；
     * 单槽位回调红线见 DomainEventSender javadoc / GC8 不注册回调）。
     *
     * @param rabbitTemplate Boot 自动装配模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     * @return 发送模板，singleton
     */
    @Bean("wardEventSender")
    public DomainEventSender wardEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, WardMessagingConstants.MODULE);
    }

    /**
     * 病房域消费模板 Bean（GC7 多实例 @Qualifier 定绑锚：四消费者构造器按名取用；
     * 标准三段式单一实现，消费者模块标识=ward）。
     *
     * @param idempotencyService 幂等构件（common 接口 / integration 实现），非空
     * @param codec              信封编解码器，非空
     * @return 消费模板，singleton
     */
    @Bean("wardConsumerSupport")
    public IdempotentConsumerSupport wardConsumerSupport(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idempotencyService, codec, WardMessagingConstants.MODULE);
    }

    /**
     * 声明 ward 模块的告警触发自事件消费队列并绑定 fy.topic（事件 V1004 id 74 已登记；
     * q.ward.iot.alarm.triggered，消费者 IotAlarmEventListener——输液告急落呼叫行）。
     *
     * <p>「每消费者一队列」形态：同路由键 iot.alarm.triggered 在 iot 侧已有
     * q.iot.iot.alarm.triggered（联动链）与 q.iot-fanout.*（扇出链）两队列，ward 队列独立
     * 消费互不竞争；幂等域 consumer_module=ward 独立派生，防他域 PROCESSED 行经回查抑制本链。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables wardAlarmTriggeredConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(WardMessagingConstants.MODULE, WardMessagingConstants.EVENT_IOT_ALARM_TRIGGERED));
    }

    /**
     * 声明 ward 模块的遥测断流异常消费队列并绑定 fy.topic（事件 V1004 id 78 已登记；
     * q.ward.iot.telemetry.anomaly，消费者 TelemetryAnomalyEventListener——体征采集质量注记）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables wardTelemetryAnomalyConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(new ConsumerQueueSpec(
                WardMessagingConstants.MODULE, WardMessagingConstants.EVENT_IOT_TELEMETRY_ANOMALY));
    }

    /**
     * 声明 ward 模块的拔针复位消费队列并绑定 fy.topic（事件 V800 id 63 已登记；事件登记在位、
     * nursing 发布端 P2 PR-3 实装——本 PR 落订阅常量+消费骨架，PR-3 发布后生效，联调债申报）；
     * q.ward.nursing.infusion.completed，消费者 NursingInfusionCompletedListener。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables wardInfusionCompletedConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(new ConsumerQueueSpec(
                WardMessagingConstants.MODULE, WardMessagingConstants.EVENT_NURSING_INFUSION_COMPLETED));
    }

    /**
     * 声明 ward 模块的设备呼叫触发消费队列并绑定 fy.topic（事件 V1004 id 81 已登记；
     * q.ward.iot.call.triggered，消费者 CallTriggeredEventListener——M16-01 呼叫信令面的设备源
     * 落行链；iot 侧 CALL_TRANSFER 联动动作经本事件扇出至 ward，Task 9 联调债随 Task 12 审查
     * Important-1 回接闭合）。
     *
     * @param governance 消息治理构件，非空；来源：integration MessagingGovernanceConfig 装配
     * @return 声明集合（quorum 队列 + 绑定）；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables wardCallTriggeredConsumerQueue(MessagingGovernance governance) {
        return governance.declareConsumerQueue(
                new ConsumerQueueSpec(WardMessagingConstants.MODULE, WardMessagingConstants.EVENT_IOT_CALL_TRIGGERED));
    }
}

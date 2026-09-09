package com.fuyun.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.internal.DeadLetterListener;
import com.fuyun.integration.properties.MessagingProperties;
import com.fuyun.integration.service.impl.EventRegistryServiceImpl;
import com.fuyun.integration.service.impl.MessageIdempotencyServiceImpl;
import com.fuyun.integration.service.impl.QueueGovernorImpl;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 消息治理装配：交换机全集、死信统一队列、信封编解码器与登记/声明构件的集中注册点（M20 §7）。
 *
 * <p>交换机全集固定 fy.topic / fy.dlx / fy.delay 三件套（全系统只此三个，任何模块禁止私建，
 * A.5-4）；死信统一队列 q.integration.dead-letter 以 "#" 全量绑定 fy.dlx；队列全 quorum 类型。
 * com.fuyun.integration 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app MessagingConfig @Import 生效（PR #4 既有裁决：装配归 app，不放宽扫描）。
 *
 * <p>EventEnvelopeCodec 为全系统发布/消费共用的信封编解码器，B2.2 死信监听依赖其 Bean 化，
 * 故随本配置一并 @Import 装配（简报 §2.7 import 清单的必要补充，PR 描述申报）。
 *
 * <p>B2.2 追加装配（简报 §2.7 预告）：MessageIdempotencyServiceImpl（消费幂等两层语义实现）
 * 与 DeadLetterListener（死信统一队列落库告警监听器）。
 *
 * <p>B2.3 追加项目标准消费容器工厂（覆盖 Boot 默认同名工厂，简报未明确处，PR 描述申报）：
 * 消费侧 payload 承接统一为原文兜底转换器——Spring AMQP 3.2 的监听链路对任何方法签名都先经
 * 转换器提取 payload（无 raw 旁路），Jackson2JsonMessageConverter 对未受信类头/毒丸报文直接抛
 * 转换异常，且无法为 String 参数还原 JSON 原文（readValue(json, String.class) 对对象节点抛
 * MismatchedInputException，均经端到端 IT 与本机实证）。故消费方一律 raw Message 参数
 * （容器经 providedArgs 注入原始帧）+ UTF-8 解码 + codec 解析（CF-1：__TypeId__ 不作消费依据），
 * 业务毒丸经有界重试耗尽正常转死信、死信监听不因二次转换失败回环。
 *
 * <p>发布确认回调说明：application.yml 已定 publisher-confirm-type: correlated 姿态（PR-1 落地）；
 * 确认回调（nack/不可路由 error 日志与补偿）随首个真实发布构件落地（PR-3 发布侧 / P1 outbox），
 * 本 PR 无生产发送代码，非遗漏（B.3-3 可靠投递属 P1 治理完整化）。
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
@Import({
    QueueGovernorImpl.class,
    EventRegistryServiceImpl.class,
    EventEnvelopeCodec.class,
    MessageIdempotencyServiceImpl.class,
    DeadLetterListener.class
})
public class MessagingGovernanceConfig {

    /**
     * 声明交换机全集与死信统一队列：三交换机（Topic，durable）+ 死信队列（quorum）
     * + 死信全量绑定（key=#）。
     *
     * @return 声明集合；由 RabbitAdmin 随连接建立幂等声明
     */
    @Bean
    public Declarables messagingExchangesAndDeadLetterQueue() {
        TopicExchange topicExchange = new TopicExchange(MessagingConstants.EXCHANGE_TOPIC);
        TopicExchange dlxExchange = new TopicExchange(MessagingConstants.EXCHANGE_DLX);
        TopicExchange delayExchange = new TopicExchange(MessagingConstants.EXCHANGE_DELAY);
        Queue deadLetterQueue = QueueBuilder.durable(MessagingConstants.QUEUE_DEAD_LETTER)
                .quorum()
                .build();
        Binding deadLetterBinding = new Binding(
                MessagingConstants.QUEUE_DEAD_LETTER,
                Binding.DestinationType.QUEUE,
                MessagingConstants.EXCHANGE_DLX,
                MessagingConstants.BINDING_KEY_ALL,
                null);
        return new Declarables(topicExchange, dlxExchange, delayExchange, deadLetterQueue, deadLetterBinding);
    }

    /**
     * 消息 JSON 转换器：以 Boot 全局定制 ObjectMapper 构建（Long→String 定制一致生效），
     * 保证消息侧与 REST 侧序列化行为一致；Boot 自动装配将其挂到 RabbitTemplate（生产发送
     * 与消费转换共用）。
     *
     * @param objectMapper Boot 自动装配的全局定制实例，非空
     * @return JSON 消息转换器
     */
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 项目标准消费容器工厂（覆盖 Boot 默认同名工厂，Boot 原工厂按同名条件让位）：
     * payload 承接换为 {@link SimpleMessageConverter}（任意报文兜底返回 byte[]，永不抛转换异常），
     * 使消费方 raw Message 参数必达原始帧；其余容器姿态（AUTO 确认、有界重试、
     * defaultRequeueRejected=false、并发等）沿用 Boot 配置器按 yml 装配，姿态不放宽。
     *
     * <p>安全边界说明：SimpleMessageConverter 仅在 contentType 为
     * application/x-java-serialized-object 时才做 Java 反序列化，本系统消息 contentType 恒为
     * application/json（发布侧 Jackson 转换器写入），不受反序列化攻击面影响。
     *
     * @param configurer        Boot 监听容器工厂配置器，非空；来源：RabbitAnnotationDrivenConfiguration
     * @param connectionFactory RabbitMQ 连接工厂，非空；来源：Boot 自动装配
     * @return 全项目 @RabbitListener 消费者的默认容器工厂
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}

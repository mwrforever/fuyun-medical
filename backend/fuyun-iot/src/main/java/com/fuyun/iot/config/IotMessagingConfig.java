package com.fuyun.iot.config;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.internal.IotEventPublisher;
import com.fuyun.iot.internal.IotFanoutListener;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * IoT 模块消息装配（B4.3，BRIEF-PR4-01 §1.5）：设备状态自事件的发布器/消费者 Bean 注册与
 * 消费队列治理声明集中点。
 *
 * <p>com.fuyun.iot 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app IotConfig @Import 生效（不放宽扫描）。队列声明走 MessagingGovernance 构件（先登记
 * 后订阅：事件已在 V403 种子登记，订阅方 iot 经声明副作用自动补登记 subscriber_modules）；
 * 交换机全集仍由 integration MessagingGovernanceConfig 声明，本配置不重复（禁私建交换机 A.5-4）。
 *
 * <p>本配置与 IotAmqpConfig（@ConditionalOnProperty enabled 开关）解耦：发布器/消费者/队列
 * 声明无条件生效——MQ 事件总线域不依赖 IoTDA AMQP 消费链开关；状态事件的产生源头（AMQP 状态
 * 帧消费）在 enabled=false 时不运行，扇出链路自然静默，零孤儿 Bean。
 */
@Configuration
@Import({IotEventPublisher.class, IotFanoutListener.class})
public class IotMessagingConfig {

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
}

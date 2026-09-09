package com.fuyun.integration.service.impl;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.DelayQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.service.IEventRegistryService;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * 队列/交换机声明构件实现：全系统消费队列与延迟队列的统一声明入口（M20 §7 治理约定）。
 *
 * <p>治理语义：事件先登记后订阅——declareConsumerQueue 先经 event_registry 校验事件已登记且
 * 未废止（未登记/已废止抛 IllegalStateException 阻断启动），再声明 {@code q.<消费者>.<事件>}
 * 队列并绑定 fy.topic。队列一律显式 quorum 类型（不依赖服务端默认值，测试与生产一致），
 * 死信统一指向 fy.dlx；延迟队列一条队列一个档位（TTL + DLX 转发，A.5-7）。
 *
 * <p>返回的 Declarables 由调用方以 @Bean 暴露，经 RabbitAdmin 幂等声明（各模块标准用法见
 * api 接口 javadoc）。命名审查（FU-M20-06）：eventType 小写点分 ≥3 段，模块/业务名非空小写。
 */
public class QueueGovernorImpl implements MessagingGovernance {

    /** 事件类型命名规则：小写点分且 ≥3 段（如 system.dict.published），首段以小写字母开头 */
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+){2,}$");

    /** 模块域/业务名命名规则：非空小写（字母开头，可含数字与连字符） */
    private static final Pattern MODULE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final IEventRegistryService eventRegistryService;

    /**
     * 全参构造器。
     *
     * @param eventRegistryService 事件契约台账服务，非空；来源：同配置类 @Import 的 EventRegistryServiceImpl
     */
    public QueueGovernorImpl(IEventRegistryService eventRegistryService) {
        this.eventRegistryService = eventRegistryService;
    }

    @Override
    public Declarables declareConsumerQueue(ConsumerQueueSpec spec) {
        validateName("consumerModule", spec.consumerModule(), MODULE_NAME_PATTERN);
        validateEventType(spec.eventType());
        // 订阅自动登记：事件未登记/已废止时此调用抛 IllegalStateException，阻断队列声明（先登记后订阅）
        eventRegistryService.registerSubscriber(spec.eventType(), spec.consumerModule());
        String queueName = MessagingConstants.QUEUE_PREFIX + spec.consumerModule() + "." + spec.eventType();
        // 不设 x-dead-letter-routing-key：死信保留原始路由键（=事件类型），死信侧据此溯源事件
        Queue queue = QueueBuilder.durable(queueName)
                .quorum()
                .deadLetterExchange(MessagingConstants.EXCHANGE_DLX)
                .build();
        Binding binding = new Binding(
                queueName, Binding.DestinationType.QUEUE, MessagingConstants.EXCHANGE_TOPIC, spec.eventType(), null);
        return new Declarables(queue, binding);
    }

    @Override
    public Declarables declareDelayQueue(DelayQueueSpec spec) {
        validateName("business", spec.business(), MODULE_NAME_PATTERN);
        Duration ttl = spec.ttl();
        // 档位校验：TTL 必须为正；x-message-ttl 服务端为 int 毫秒值，超上界必须显式拒绝而非静默溢出
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("延迟队列声明失败：ttl 必须为正且不超出 int 毫秒上界，business=" + spec.business());
        }
        if (spec.targetRoutingKey() == null || spec.targetRoutingKey().isBlank()) {
            throw new IllegalArgumentException("延迟队列声明失败：targetRoutingKey 不能为空，business=" + spec.business());
        }
        String queueName = MessagingConstants.DELAY_QUEUE_PREFIX + spec.business();
        // 一条队列一个延迟档位：到期消息经 x-dead-letter-routing-key 回投 fy.topic 目标路由键（A.5-7）
        Queue queue = QueueBuilder.durable(queueName)
                .quorum()
                .ttl((int) ttl.toMillis())
                .deadLetterExchange(MessagingConstants.EXCHANGE_TOPIC)
                .deadLetterRoutingKey(spec.targetRoutingKey())
                .build();
        Binding binding = new Binding(
                queueName, Binding.DestinationType.QUEUE, MessagingConstants.EXCHANGE_DELAY, queueName, null);
        return new Declarables(queue, binding);
    }

    /**
     * 事件类型命名审查：小写点分且 ≥3 段（FU-M20-06 命名治理）。
     *
     * @param eventType 待审查事件类型，允许为空（空值按违规处理）
     * @throws IllegalArgumentException 命名违规时触发；建议处理策略：修正装配代码中的事件类型常量
     */
    private void validateEventType(String eventType) {
        validateName("eventType", eventType, EVENT_TYPE_PATTERN);
    }

    /**
     * 通用命名审查：按给定规则校验并抛出带字段名的违规异常。
     *
     * @param fieldName 字段名（进异常消息，便于装配期定位）
     * @param value     待审查值，允许为空
     * @param pattern   命名规则
     * @throws IllegalArgumentException 取值为空或不匹配规则时触发
     */
    private void validateName(String fieldName, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("消息治理命名不合规：" + fieldName + "=\"" + value + "\" 不符合约定命名规则");
        }
    }
}

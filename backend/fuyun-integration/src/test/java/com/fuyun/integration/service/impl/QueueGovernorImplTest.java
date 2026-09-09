package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.DelayQueueSpec;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.service.IEventRegistryService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

/**
 * 队列/交换机声明构件单元测试：验证消费队列/延迟队列的命名、quorum 类型、死信参数与登记联动。
 *
 * <p>治理语义断言（M20）：事件先登记后订阅（未登记抛 IllegalStateException 阻断启动）、
 * 消费队列不设死信路由键（保留原始路由键）、延迟队列 TTL+DLX 转发参数完备、命名审查拒绝违规值。
 * IEventRegistryService 以 Mockito 模拟，聚焦声明构件自身的编排与校验逻辑。
 */
@ExtendWith(MockitoExtension.class)
class QueueGovernorImplTest {

    @Mock
    private IEventRegistryService eventRegistryService;

    @InjectMocks
    private QueueGovernorImpl queueGovernor;

    @Test
    @DisplayName("declareConsumerQueue：声明 q.<消费者>.<事件> quorum 队列，死信指向 fy.dlx 且不设死信路由键，绑定 fy.topic")
    void declareConsumerQueueCreatesQuorumQueueWithDeadLetterRoutingAndBinding() {
        Declarables declarables =
                queueGovernor.declareConsumerQueue(new ConsumerQueueSpec("it", "system.dict.published"));

        Queue queue = singleQueueOf(declarables);
        assertThat(queue.getName()).isEqualTo("q.it.system.dict.published");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry(MessagingConstants.X_QUEUE_TYPE, MessagingConstants.QUEUE_TYPE_QUORUM)
                .containsEntry(MessagingConstants.X_DEAD_LETTER_EXCHANGE, MessagingConstants.EXCHANGE_DLX)
                // 不设 x-dead-letter-routing-key：死信保留原始路由键（=事件类型），死信侧据此溯源
                .doesNotContainKey(MessagingConstants.X_DEAD_LETTER_ROUTING_KEY);

        Binding binding = singleBindingOf(declarables);
        assertThat(binding.getExchange()).isEqualTo(MessagingConstants.EXCHANGE_TOPIC);
        assertThat(binding.getRoutingKey()).isEqualTo("system.dict.published");
        assertThat(binding.getDestination()).isEqualTo("q.it.system.dict.published");
        // 订阅自动登记：声明消费队列即视为该模块订阅该事件
        verify(eventRegistryService).registerSubscriber("system.dict.published", "it");
    }

    @Test
    @DisplayName("declareConsumerQueue：事件未登记时 registerSubscriber 抛 IllegalStateException 阻断声明")
    void declareConsumerQueueFailsFastWhenEventNotRegistered() {
        doThrow(new IllegalStateException("事件未在 event_registry 登记"))
                .when(eventRegistryService)
                .registerSubscriber("system.unknown.event", "it");

        assertThatThrownBy(
                        () -> queueGovernor.declareConsumerQueue(new ConsumerQueueSpec("it", "system.unknown.event")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event_registry");
        verify(eventRegistryService).registerSubscriber("system.unknown.event", "it");
    }

    @Test
    @DisplayName("declareConsumerQueue：eventType 大写命名违规抛 IllegalArgumentException，且不触发订阅登记")
    void declareConsumerQueueRejectsUppercaseEventType() {
        assertThatThrownBy(
                        () -> queueGovernor.declareConsumerQueue(new ConsumerQueueSpec("it", "System.Dict.Published")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        verifyNoInteractions(eventRegistryService);
    }

    @Test
    @DisplayName("declareConsumerQueue：eventType 仅两段命名违规抛 IllegalArgumentException")
    void declareConsumerQueueRejectsTwoSegmentEventType() {
        assertThatThrownBy(() -> queueGovernor.declareConsumerQueue(new ConsumerQueueSpec("it", "system.published")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        verifyNoInteractions(eventRegistryService);
    }

    @Test
    @DisplayName("declareConsumerQueue：consumerModule 空白或含大写抛 IllegalArgumentException")
    void declareConsumerQueueRejectsInvalidConsumerModule() {
        assertThatThrownBy(
                        () -> queueGovernor.declareConsumerQueue(new ConsumerQueueSpec("IT", "system.dict.published")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerModule");
        assertThatThrownBy(
                        () -> queueGovernor.declareConsumerQueue(new ConsumerQueueSpec(" ", "system.dict.published")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerModule");
        verifyNoInteractions(eventRegistryService);
    }

    @Test
    @DisplayName("declareDelayQueue：声明 delay.<业务> 档位队列，TTL+fy.topic 死信转发与目标路由键完备，绑定 fy.delay")
    void declareDelayQueueCreatesTtlQueueWithForwardingParameters() {
        Duration ttl = Duration.ofMinutes(30);

        Declarables declarables =
                queueGovernor.declareDelayQueue(new DelayQueueSpec("order-close", ttl, "order.close.timeout"));

        Queue queue = singleQueueOf(declarables);
        assertThat(queue.getName()).isEqualTo("delay.order-close");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry(MessagingConstants.X_QUEUE_TYPE, MessagingConstants.QUEUE_TYPE_QUORUM)
                .containsEntry(MessagingConstants.X_MESSAGE_TTL, (int) ttl.toMillis())
                .containsEntry(MessagingConstants.X_DEAD_LETTER_EXCHANGE, MessagingConstants.EXCHANGE_TOPIC)
                .containsEntry(MessagingConstants.X_DEAD_LETTER_ROUTING_KEY, "order.close.timeout");

        // 一条延迟队列一个档位，绑定到 fy.delay（key=队列名），到期经 DLX 参数回投 fy.topic 目标路由键
        Binding binding = singleBindingOf(declarables);
        assertThat(binding.getExchange()).isEqualTo(MessagingConstants.EXCHANGE_DELAY);
        assertThat(binding.getRoutingKey()).isEqualTo("delay.order-close");
        assertThat(binding.getDestination()).isEqualTo("delay.order-close");
    }

    @Test
    @DisplayName("declareDelayQueue：ttl 为零或负数或溢出 int 时拒绝声明")
    void declareDelayQueueRejectsNonPositiveOrOverflowingTtl() {
        assertThatThrownBy(() -> queueGovernor.declareDelayQueue(
                        new DelayQueueSpec("order-close", Duration.ZERO, "order.close.timeout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
        assertThatThrownBy(() -> queueGovernor.declareDelayQueue(
                        new DelayQueueSpec("order-close", Duration.ofSeconds(-1), "order.close.timeout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
        // x-message-ttl 服务端为 int 毫秒值，超过 int 上界的档位必须显式拒绝而非静默溢出
        assertThatThrownBy(() -> queueGovernor.declareDelayQueue(
                        new DelayQueueSpec("order-close", Duration.ofDays(365), "order.close.timeout")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    @DisplayName("declareDelayQueue：目标路由键空白时拒绝声明")
    void declareDelayQueueRejectsBlankTargetRoutingKey() {
        assertThatThrownBy(() ->
                        queueGovernor.declareDelayQueue(new DelayQueueSpec("order-close", Duration.ofMinutes(5), " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetRoutingKey");
    }

    /**
     * 从声明集合中取唯一队列，保证构件每次只声明一条队列。
     *
     * @param declarables 构件返回的声明集合，非空
     * @return 唯一的 Queue 声明
     */
    private Queue singleQueueOf(Declarables declarables) {
        List<Declarable> queues = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .toList();
        assertThat(queues).hasSize(1);
        return (Queue) queues.get(0);
    }

    /**
     * 从声明集合中取唯一绑定，保证构件每次只声明一条绑定。
     *
     * @param declarables 构件返回的声明集合，非空
     * @return 唯一的 Binding 声明
     */
    private Binding singleBindingOf(Declarables declarables) {
        List<Declarable> bindings = declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .toList();
        assertThat(bindings).hasSize(1);
        return (Binding) bindings.get(0);
    }
}

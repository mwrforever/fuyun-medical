package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.service.IEventRegistryService;
import com.fuyun.integration.service.impl.QueueGovernorImpl;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

/**
 * 护理域消息装配布线回归测试：taskOverdueDelayQueue 档位声明经真治理构件（QueueGovernorImpl）
 * 必须可产出——曾以整队列名（delay.task-overdue，含点）充当 business 段入参，被命名审查
 * ^[a-z][a-z0-9-]*$ 拒绝致启动阻断（单测不加载装配类故漏网）；本测试以真实构件直调配置方法
 * 把守该布线，business 段违规即抛 IllegalArgumentException 失败。
 */
@ExtendWith(MockitoExtension.class)
class NursingMessagingConfigTest {

    /** 事件契约台账服务：延迟档位声明链路不触登记（declareDelayQueue 仅命名与 TTL 校验），Mock 占位即可 */
    @Mock
    private IEventRegistryService eventRegistryService;

    @Test
    @DisplayName("taskOverdueDelayQueue：business 传无点段，经真治理构件声明出 delay.task-overdue 档位队列（TTL/死信/绑定完备）")
    void taskOverdueDelayQueueDeclaresGovernedDelayQueueWithBusinessSegment() {
        Declarables declarables =
                new NursingMessagingConfig().taskOverdueDelayQueue(new QueueGovernorImpl(eventRegistryService));

        List<Queue> queues = declarables.getDeclarables().stream()
                .filter(d -> d instanceof Queue)
                .map(d -> (Queue) d)
                .toList();
        List<Binding> bindings = declarables.getDeclarables().stream()
                .filter(d -> d instanceof Binding)
                .map(d -> (Binding) d)
                .toList();
        // 档位队列名与常量同源（V805 头注口径），拼装来源=delay. 前缀 + task-overdue 业务段
        assertThat(queues).hasSize(1);
        assertThat(queues.get(0).getName()).isEqualTo(NursingMessagingConstants.DELAY_QUEUE_TASK_OVERDUE);
        assertThat(queues.get(0).isDurable()).isTrue();
        assertThat(queues.get(0).getArguments())
                .containsEntry(MessagingConstants.X_QUEUE_TYPE, MessagingConstants.QUEUE_TYPE_QUORUM)
                // 到期死信回投 fy.topic，路由键钉 tick 键（监听器消费驱动逾期扫描）
                .containsEntry(MessagingConstants.X_DEAD_LETTER_EXCHANGE, MessagingConstants.EXCHANGE_TOPIC)
                .containsEntry(
                        MessagingConstants.X_DEAD_LETTER_ROUTING_KEY,
                        NursingMessagingConstants.ROUTING_TASK_OVERDUE_TICK)
                // Task 9 档位口径：TTL=60 秒（自续期心跳帧周期）
                .containsEntry(MessagingConstants.X_MESSAGE_TTL, 60000);
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).getExchange()).isEqualTo(MessagingConstants.EXCHANGE_DELAY);
        assertThat(bindings.get(0).getDestination()).isEqualTo(NursingMessagingConstants.DELAY_QUEUE_TASK_OVERDUE);
    }
}

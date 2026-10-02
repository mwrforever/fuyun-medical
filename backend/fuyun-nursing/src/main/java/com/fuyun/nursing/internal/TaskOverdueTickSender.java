package com.fuyun.nursing.internal;

import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 任务逾期 tick 心跳帧发送器（P2 PR-3 Task 9，归 internal/ 禁外引；Bean 注册点
 * NursingMessagingConfig @Import）：向 fy.delay 延迟档位 delay.task-overdue 投递空载荷
 * ping 帧（自续期链路只需存在性不需内容——dispatch 建议口径），TTL=60 秒（档位声明冻结，
 * A.5-7 单档位语义）到期经 DLX 以 nursing.task-overdue.tick 路由键回 fy.topic 驱动下一轮
 * 扫描。tick 非领域事件不入 event_registry、不经 EventEnvelopeCodec（无信封无幂等键——
 * 幂等由扫描侧 CAS 谓词与空 tick 零副作用承载）；并供启动播种器惰性判积压（messageCount
 * 无副作用查询）。不注册 Confirm/Returns 回调（共享单槽位红线，GC8）。
 * 线程安全：无状态单例。
 */
@Slf4j
public class TaskOverdueTickSender {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import）。
     *
     * @param rabbitTemplate Boot 自动装配发送模板，非空
     */
    public TaskOverdueTickSender(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 投递 tick 心跳帧（空载荷 ping：自续期链路只需存在性；CorrelationData 携随机 UUID——
     * 共享确认回调失败帧定位锚，DomainEventSender 同款口径）。
     */
    public void sendTick() {
        // 空串 ping 帧：延迟档位驻留 60 秒后经死信参数回投 fy.topic 路由键 nursing.task-overdue.tick
        rabbitTemplate.convertAndSend(
                MessagingConstants.EXCHANGE_DELAY,
                NursingMessagingConstants.DELAY_QUEUE_TASK_OVERDUE,
                "",
                new CorrelationData(UUID.randomUUID().toString()));
        log.debug("任务逾期 tick 心跳帧已入延迟档位：delayQueue={}", NursingMessagingConstants.DELAY_QUEUE_TASK_OVERDUE);
    }

    /**
     * 延迟档位积压惰性判定（启动播种幂等检查）：messageCount 为被动无副作用查询
     * （不消费消息）——已有积压即自续期链路在飞，播种跳过。
     *
     * @return true=档位内已有待到期帧（链路在飞）
     */
    public boolean hasPendingTick() {
        // amqp-client 5.x messageCount 返回 long（被动声明+计数，零消费零副作用）
        Long count = rabbitTemplate.execute(
                channel -> channel.messageCount(NursingMessagingConstants.DELAY_QUEUE_TASK_OVERDUE));
        return count != null && count > 0;
    }
}

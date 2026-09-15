package com.fuyun.integration.config;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.controller.MdmSubscriptionController;
import com.fuyun.integration.internal.MdmDispatchListener;
import com.fuyun.integration.service.impl.MdmSubscriptionServiceImpl;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M20 主数据分发治理装配：订阅台账服务/端点 + 分发流水消费者与五个消费队列声明。
 *
 * <p>队列经治理构件声明（先登记后订阅 + quorum + 死信 fy.dlx，M20 §7 治理约定）：声明副作用同时把
 * `integration` 写入五个主数据事件的 subscriber_modules（事件已在 V5 种子登记）；队列名与消费者
 * {@code @RabbitListener} 同源推导（同一组常量），禁手写队列字面量。
 */
@Configuration
@Import({MdmSubscriptionServiceImpl.class, MdmSubscriptionController.class, MdmDispatchListener.class})
public class IntegrationMdmConfig {

    /**
     * 声明五个主数据消费队列（q.integration.<事件类型>）并绑定 fy.topic。
     *
     * @param governance 消息治理构件，非空；来源：MessagingGovernanceConfig 装配
     * @return 声明集合（五队列 + 五绑定）交 RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables mdmMasterDataQueues(MessagingGovernance governance) {
        List<Declarable> declarables = Stream.of(
                        MdmConstants.EVENT_DICT_PUBLISHED,
                        MdmConstants.EVENT_ORG_CHANGED,
                        MdmConstants.EVENT_USER_CHANGED,
                        MdmConstants.EVENT_PARAM_CHANGED,
                        MdmConstants.EVENT_PRACTICE_CHANGED)
                .flatMap(eventType -> governance
                        .declareConsumerQueue(new ConsumerQueueSpec(MessagingConstants.MODULE, eventType))
                        .getDeclarables()
                        .stream())
                .toList();
        return new Declarables(declarables);
    }
}

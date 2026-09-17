package com.fuyun.patient.config;

import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.internal.PatientCacheInvalidationListener;
import com.fuyun.patient.internal.PatientDuplicateScanJob;
import com.fuyun.patient.internal.PatientEventPublisher;
import java.util.Set;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M02 消息装配：事件发布器 / 自事件缓存失效消费者 / 六队列治理声明 / 批量扫描任务集中点
 * （本类由 fuyun-app PatientConfig @Import 生效；交换机全集归 integration，禁私建 A.5-4）。
 */
@Configuration
@Import({PatientEventPublisher.class, PatientCacheInvalidationListener.class, PatientDuplicateScanJob.class})
public class PatientMessagingConfig {

    /**
     * 声明六个自消费队列并绑定 fy.topic（事件未登记时构件抛异常阻断启动；V105 种子先行）。
     *
     * @param governance 消息治理构件，非空
     * @return 声明集合（quorum 队列 + 绑定）；RabbitAdmin 幂等声明
     */
    @Bean
    public Declarables patientSelfConsumerQueues(MessagingGovernance governance) {
        Set<String> eventTypes = PatientMessagingConstants.CACHE_EVICTION_EVENT_TYPES;
        return new Declarables(eventTypes.stream()
                .map(eventType -> governance.declareConsumerQueue(
                        new ConsumerQueueSpec(PatientMessagingConstants.MODULE, eventType)))
                .flatMap(ds -> ds.getDeclarables().stream())
                .toList());
    }
}

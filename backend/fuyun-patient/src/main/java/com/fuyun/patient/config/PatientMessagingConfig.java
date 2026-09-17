package com.fuyun.patient.config;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.patient.constants.PatientMessagingConstants;
import com.fuyun.patient.internal.PatientCacheInvalidationListener;
import com.fuyun.patient.internal.PatientDuplicateScanJob;
import com.fuyun.patient.internal.PatientEventPublisher;
import java.util.Set;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    /** 患者域发送模板 Bean（common 基类 + 模块标识；单槽位回调红线见 PatientEventPublisher javadoc；
     *  Bean 名=注入锚点，PatientEventPublisher 构造器 @Qualifier("patientEventSender") 定绑） */
    @Bean
    public DomainEventSender patientEventSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        return new DomainEventSender(rabbitTemplate, codec, PatientMessagingConstants.MODULE);
    }

    /**
     * 患者域幂等消费模板 Bean（common 基类 + MODULE 标识）。
     *
     * @param idem  幂等构件（integration 实现），非空
     * @param codec 信封编解码器，非空
     * @return 消费模板；Bean 名=PatientCacheInvalidationListener 构造器
     *         @Qualifier("patientConsumerSupport") 注入锚点（fuyun-app 上下文与 billing 侧同型双候选，
     *         第 2 轮审查 P0-1 显式定绑）
     */
    @Bean
    public IdempotentConsumerSupport patientConsumerSupport(MessageIdempotencyService idem, EventEnvelopeCodec codec) {
        return new IdempotentConsumerSupport(idem, codec, PatientMessagingConstants.MODULE);
    }
}

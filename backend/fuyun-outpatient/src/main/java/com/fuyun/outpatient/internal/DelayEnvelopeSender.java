package com.fuyun.outpatient.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 延迟信封发送器（fy.delay 单档位 delay.appointment-timeout 专用；归 internal/ 禁外引，Bean 注册点
 * OutpatientMessagingConfig @Import）：PORTAL 预约占位时投递 AppointmentTimeoutPayload 信封入
 * 延迟队列，TTL=支付时限（OutpatientProperties.appointmentTimeout 与档位声明同源）；到期经 DLX 以
 * outpatient.appointment.timeout 路由键回 fy.topic，由本模块超时监听器消费。
 *
 * <p><b>「事务内禁 MQ 发送」红线（A.4.2-7）的显式例外与裁决链</b>：延迟信封入队属「占位登记」动作
 * 而非业务结果通知——投递语义为 at-least-once 的预期消息（计划 Task 5 Interfaces 例外注记）；消费侧
 * （OutpatientAppointmentTimeoutListener → markTimeout）以预约单状态 CAS（RESERVED→NO_SHOW 影响
 * 1 行才执行释放面）定性幂等，重复/迟到信封零二次释放，事务回滚残留信封无业务副作用，故允许事务内
 * 直发而不必走 AFTER_COMMIT。不注册 Confirm/Returns 回调（共享单槽位归 SystemEventPublisher，
 * TASK.md W-11）。线程安全：无状态单例。
 */
@Slf4j
public class DelayEnvelopeSender {

    /** 延迟交换机（治理三件套之一，禁私建，MessagingConstants.EXCHANGE_DELAY 同值） */
    private static final String DELAY_EXCHANGE = "fy.delay";

    /** 延迟档位路由键（=延迟队列名 delay.appointment-timeout，与 OutpatientMessagingConfig 声明同源） */
    private static final String DELAY_ROUTING_KEY = "delay.appointment-timeout";

    /** traceId MDC 键（与 fuyun.trace.mdc-key 配置一致，DomainEventSender 同款取值口径） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final RabbitTemplate rabbitTemplate;

    private final EventEnvelopeCodec codec;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import）。
     *
     * @param rabbitTemplate Boot 自动装配发送模板，非空
     * @param codec          信封编解码器（MessagingGovernanceConfig 装配），非空
     */
    public DelayEnvelopeSender(RabbitTemplate rabbitTemplate, EventEnvelopeCodec codec) {
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
    }

    /**
     * 投递预约支付超时延迟信封（生产者事务内调用，例外语义见类 javadoc）。
     *
     * @param payload 超时回调载荷（apptNo/patientId/poolId，V204 id 39 冻结契约），非空
     */
    public void send(AppointmentTimeoutPayload payload) {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                OutpatientMessagingConstants.MODULE,
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT,
                MDC.get(TRACE_ID_MDC_KEY),
                payload);
        // CorrelationData 携带 eventId：共享确认回调据此定位失败帧（告警留痕，DomainEventSender 同款）
        rabbitTemplate.convertAndSend(
                DELAY_EXCHANGE, DELAY_ROUTING_KEY, envelope, new CorrelationData(envelope.eventId()));
        log.info(
                "延迟信封已入队：eventType={}，routingKey={}，eventId={}，apptNo={}，poolId={}",
                envelope.eventType(),
                DELAY_ROUTING_KEY,
                envelope.eventId(),
                payload.apptNo(),
                payload.poolId());
    }
}

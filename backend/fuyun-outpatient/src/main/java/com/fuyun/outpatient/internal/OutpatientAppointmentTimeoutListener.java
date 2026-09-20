package com.fuyun.outpatient.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IAppointmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 预约支付超时回调消费侧（outpatient.appointment.timeout，V204 id 39 自产自消内部事件）：
 * fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic，驱动超时占位预约置 NO_SHOW、
 * 号源回池与爽约信用记录（释放面幂等由 appointmentService.markTimeout 业务态 CAS 守卫承载）。
 * 归 internal/，Bean 注册点 OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientAppointmentTimeoutListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IAppointmentService appointmentService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport    消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param appointmentService 预约服务，非空
     */
    public OutpatientAppointmentTimeoutListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IAppointmentService appointmentService) {
        this.consumerSupport = consumerSupport;
        this.appointmentService = appointmentService;
    }

    /**
     * 预约支付超时回调事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT)
    public void onAppointmentTimeout(Message message) {
        consumerSupport.consume(message, (EventEnvelope envelope) -> {
            // payload 三字段非空守卫（Long 以文本承载——JacksonLongToStringConfig 线格式，0 兜底值即
            // 不合规帧显式抛出进死信留痕；apptNo 空白同此）
            String apptNo = envelope.payload().path("apptNo").asText("");
            long patientId = envelope.payload().path("patientId").asLong(0L);
            long poolId = envelope.payload().path("poolId").asLong(0L);
            if (apptNo.isBlank() || patientId == 0L || poolId == 0L) {
                throw new IllegalStateException("预约超时回调载荷不合规（缺 apptNo/patientId/poolId）：eventType="
                        + envelope.eventType() + "，payload=" + envelope.payload());
            }
            appointmentService.markTimeout(new AppointmentTimeoutPayload(apptNo, patientId, poolId));
        });
    }
}

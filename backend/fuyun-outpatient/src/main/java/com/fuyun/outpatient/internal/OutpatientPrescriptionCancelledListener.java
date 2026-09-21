package com.fuyun.outpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 处方作废回流消费侧（pharmacy.prescription.cancelled，V702 id 26 既有登记；M06 作废链回流
 * 唯一通道）：RX_REF 引用行 CANCELLED 联动（Spec :119 R2-10 回流驱动）业务分发面。载荷锚守卫归
 * IChargingService.onPrescriptionCancelled（业务错误口径统一收口），本监听器仅做五组件解析与
 * 三段式委托。归 internal/，Bean 注册点 OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientPrescriptionCancelledListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IChargingService chargingService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport 消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param chargingService 收费编排服务，非空；处方作废回流业务体（引用行三态 CAS 作废）
     */
    public OutpatientPrescriptionCancelledListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IChargingService chargingService) {
        this.consumerSupport = consumerSupport;
        this.chargingService = chargingService;
    }

    /**
     * 处方作废回流事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED)
    public void onPrescriptionCancelled(Message message) {
        consumerSupport.consume(message, this::handlePrescriptionCancelled);
    }

    /**
     * 回流业务体（包级可见供单测直驱；@RabbitListener 入口仅做 consume 委托）：V702 id 26 五组件
     * 解析后委托收费编排。
     *
     * @param envelope 已解码信封，非空
     */
    void handlePrescriptionCancelled(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        chargingService.onPrescriptionCancelled(new PrescriptionCancelledPayload(
                payload.path("prescriptionId").asText(""),
                payload.path("rxNo").asText(""),
                payload.path("patientId").asLong(0L),
                payload.path("visitId").asText(""),
                payload.path("reason").asText("")));
    }
}

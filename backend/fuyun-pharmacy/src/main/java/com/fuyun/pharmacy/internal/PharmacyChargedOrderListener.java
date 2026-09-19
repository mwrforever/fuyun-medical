package com.fuyun.pharmacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 门诊放行事件消费侧（outpatient.order.charged 占位订阅，V702 id 25）：门诊/急诊处方
 * 转 PENDING_DISPENSE 的唯一权威放行通道（Spec §3.1）。载荷正式字段随 PR-5 冻结——
 * 现按最小已知字段 visitId 维度放行（主控裁决 4：IT 合成信封注入驱动；发布方缺 visitId
 * 视为不合规帧抛出进死信留痕）。
 *
 * <p>幂等边界：eventId 构件幂等 + 业务级 CAS 重读定性（DispenseServiceImpl.releaseByVisit）；
 * 归 internal/，Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyChargedOrderListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispenseService dispenseService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑）。
     *
     * @param consumerSupport 消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param dispenseService 发药服务，非空
     */
    public PharmacyChargedOrderListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispenseService dispenseService) {
        this.consumerSupport = consumerSupport;
        this.dispenseService = dispenseService;
    }

    /**
     * 缴费放行事件入口。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CHARGED)
    public void onOrderCharged(Message message) {
        consumerSupport.consume(message, envelope -> {
            JsonNode payload = envelope.payload();
            String visitId = payload.path("visitId").asText(null);
            if (visitId == null || visitId.isBlank()) {
                // 占位契约不合规帧显式暴露（PR-5 冻结载荷后校验随其字段集更新）
                throw new IllegalStateException(
                        "缴费放行事件载荷不合规（缺 visitId）：eventType=" + envelope.eventType() + "，payload=" + payload);
            }
            dispenseService.releaseByVisit(visitId);
        });
    }
}

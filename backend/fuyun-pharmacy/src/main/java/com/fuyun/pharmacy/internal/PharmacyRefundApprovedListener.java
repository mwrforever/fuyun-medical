package com.fuyun.pharmacy.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 退费审批回执消费侧（billing.refund.approved，V605 id 20 既有登记）：以 M13 回执为退费权威，
 * 驱动已受理退药终态的发药单向处方镜像终态（DISPENSED→PART/FULL_RETURNED，Spec :132）。
 *
 * <p>双通道终态收敛说明：本消费者（refund.approved）与 order.cancelled（PR-5 回切）双通道
 * 依赖 eventId 构件幂等（IdempotentConsumerSupport 去重）+ 业务级终态重读跳过
 * （DispenseServiceImpl.confirmRefundTerminal 已终态不再迁移）保证重复到达只收敛一次。
 * 归 internal/，Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyRefundApprovedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispenseService dispenseService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑）。
     *
     * @param consumerSupport 消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param dispenseService 发药服务，非空
     */
    public PharmacyRefundApprovedListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispenseService dispenseService) {
        this.consumerSupport = consumerSupport;
        this.dispenseService = dispenseService;
    }

    /**
     * 退费审批通过事件入口（载荷最小已知字段 patientId，PR-5 冻结正式载荷后校验随其更新）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED)
    public void onRefundApproved(Message message) {
        consumerSupport.consume(message, (EventEnvelope envelope) -> {
            // patientId 以文本承载（Long→String 序列化），0 兜底值即不合规帧显式抛出进死信留痕
            long patientId = envelope.payload().path("patientId").asLong(0L);
            if (patientId == 0L) {
                throw new IllegalStateException("退费审批回执载荷不合规（缺 patientId）：eventType=" + envelope.eventType()
                        + "，payload=" + envelope.payload());
            }
            dispenseService.confirmRefundTerminal(patientId);
        });
    }
}

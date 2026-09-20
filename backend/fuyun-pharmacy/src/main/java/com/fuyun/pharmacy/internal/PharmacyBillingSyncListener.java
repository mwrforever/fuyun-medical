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
 * 收费域回执消费侧（billing.fee.created，V605 id 17 既有登记）：PENDING 费用生成驱动
 * 处方 APPROVED→PENDING_FEE（Spec R2-14）。refund.approved 消费随 Task 7 追加本类。
 *
 * <p>归 internal/，Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyBillingSyncListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispenseService dispenseService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板 @Qualifier 定绑）。
     *
     * @param consumerSupport 消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param dispenseService 发药服务，非空
     */
    public PharmacyBillingSyncListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispenseService dispenseService) {
        this.consumerSupport = consumerSupport;
        this.dispenseService = dispenseService;
    }

    /**
     * 费用生成回执入口（载荷 FeeCreatedPayload，billingKey 第三段守卫在服务侧）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED)
    public void onFeeCreated(Message message) {
        consumerSupport.consume(message, (EventEnvelope envelope) -> {
            String billingKey = envelope.payload().path("billingKey").asText(null);
            if (billingKey == null || billingKey.isBlank()) {
                throw new IllegalStateException("费用生成回执载荷不合规（缺 billingKey）：payload=" + envelope.payload());
            }
            dispenseService.markPendingFee(billingKey);
        });
    }
}

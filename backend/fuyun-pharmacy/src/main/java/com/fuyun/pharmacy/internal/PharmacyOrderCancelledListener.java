package com.fuyun.pharmacy.internal;

import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 门诊退费逆向占位消费侧（outpatient.order.cancelled，V702 占位 id 31）：本消费者仅落码与
 * 中文日志留痕，不承载任何业务迁移——Spec :209 中该事件在本模块的「终态确认」职责
 * （未发药处方作废/已退药单据终态归并）随 PR-5 回切实装（登记依据 id 31，生产发布方亦为
 * PR-5 回切，本期零发布）。
 *
 * <p>幂等边界：eventId 构件幂等（IdempotentConsumerSupport 去重）+ 本体零写面直返，
 * 重复投递无副作用。归 internal/，Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyOrderCancelledListener {

    private final IdempotentConsumerSupport consumerSupport;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑）。
     *
     * @param consumerSupport 消费模板，非空；定绑 pharmacyConsumerSupport Bean
     */
    public PharmacyOrderCancelledListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport) {
        this.consumerSupport = consumerSupport;
    }

    /**
     * 门诊退费逆向事件入口（占位消费：留痕即直返，业务终态确认 PR-5 回切）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CANCELLED)
    public void onOrderCancelled(Message message) {
        consumerSupport.consume(
                message,
                envelope -> log.info(
                        "门诊退费逆向事件留痕（占位消费，终态确认职责 PR-5 回切）：eventType={}，payload={}",
                        envelope.eventType(),
                        envelope.payload()));
    }
}

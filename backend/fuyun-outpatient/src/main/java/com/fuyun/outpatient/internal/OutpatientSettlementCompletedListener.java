package com.fuyun.outpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 结算完成消费侧（billing.settlement.completed，V605 id 19 既有登记；M03 收费编排唯一权威回执）：
 * 单据精确放行扇出（order.charged）与挂号费收费回填（fee_status=PAID+fee_settlement_id 回填，
 * PAID⇒visit 锚在位）的业务分发面。载荷锚守卫归 IChargingService.onSettlementCompleted（业务
 * 错误口径统一收口），本监听器仅做十组件解析与三段式委托。归 internal/，Bean 注册点
 * OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientSettlementCompletedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IChargingService chargingService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport 消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param chargingService 收费编排服务，非空；结算回执业务体（放行扇出+挂号费回填分发）
     */
    public OutpatientSettlementCompletedListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IChargingService chargingService) {
        this.consumerSupport = consumerSupport;
        this.chargingService = chargingService;
    }

    /**
     * 结算完成事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_SUB_BILLING_SETTLEMENT_COMPLETED)
    public void onSettlementCompleted(Message message) {
        consumerSupport.consume(message, this::handleSettlementCompleted);
    }

    /**
     * 回执业务体（包级可见供单测直驱；@RabbitListener 入口仅做 consume 委托）：V605 id 19 十组件
     * 解析后委托收费编排（金额缺失落 0 由业务侧 0 元拒绝口径统一拒绝，禁静默放行）。
     *
     * @param envelope 已解码信封，非空
     */
    void handleSettlementCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        chargingService.onSettlementCompleted(new SettlementCompletedPayload(
                payload.path("settlementId").asLong(0L),
                payload.path("settleNo").asText(""),
                payload.path("patientId").asLong(0L),
                payload.path("visitId").asText(""),
                payload.path("settleType").asText(""),
                payload.path("payerType").asText(""),
                payload.path("totalAmount").asLong(0L),
                payload.path("pooledAmount").asLong(0L),
                payload.path("acctPayAmount").asLong(0L),
                payload.path("selfPayAmount").asLong(0L)));
    }
}

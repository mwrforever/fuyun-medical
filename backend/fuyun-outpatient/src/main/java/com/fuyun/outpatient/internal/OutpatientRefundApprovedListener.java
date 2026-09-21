package com.fuyun.outpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IChargingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 退费审批通过消费侧（billing.refund.approved，V605 id 20 既有登记；退号退费联动唯一终态权威）：
 * 免审直退（DAY_CORRECTION autoApproved）apply 同事务发布立达，跨日分级审批终批同事件承载——
 * appointment/visit 终态一律本回执后置（资金无涉红线裁决 7：禁止本模块自行「退款成功」），驱动
 * 退号预约/取号单置 CANCELLED、号源回池、fee_status=REFUNDED 与 visit REGISTERED→CANCELLED 回滚
 * （appointment 分支，Task 6）；同回执再按结算锚反查来源单据驱动开单退费逆向与 order.cancelled
 * 逐单扇出（order 分支，Task 10，appointment 分支之后追加）。归 internal/，Bean 注册点
 * OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientRefundApprovedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IAppointmentService appointmentService;

    private final IChargingService chargingService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport    消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param appointmentService 预约服务，非空；appointment 分支（退号终态/回池/visit 回滚）
     * @param chargingService    收费编排服务，非空；order 分支（单据逆向+order.cancelled 扇出）
     */
    public OutpatientRefundApprovedListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IAppointmentService appointmentService,
            IChargingService chargingService) {
        this.consumerSupport = consumerSupport;
        this.appointmentService = appointmentService;
        this.chargingService = chargingService;
    }

    /**
     * 退费审批通过事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED)
    public void onRefundApproved(Message message) {
        consumerSupport.consume(message, this::handleRefundApproved);
    }

    /**
     * 回执业务体（包级可见供单测直驱；@RabbitListener 入口仅做 consume 委托）：payload 七组件解析
     * 与退号退费回执分发。
     *
     * @param envelope 已解码信封，非空
     */
    void handleRefundApproved(EventEnvelope envelope) {
        // settlementId 非空守卫（brief 冻结）：缺锚帧显式抛出进死信留痕（禁静默丢回执——回执即终态权威）
        JsonNode settlementNode = envelope.payload().path("settlementId");
        if (settlementNode.isMissingNode() || settlementNode.isNull() || settlementNode.asLong(0L) == 0L) {
            throw new IllegalStateException(
                    "退费回执载荷不合规（缺 settlementId）：eventType=" + envelope.eventType() + "，payload=" + envelope.payload());
        }
        RefundApprovedPayload payload = new RefundApprovedPayload(
                envelope.payload().path("refundId").asLong(0L),
                envelope.payload().path("refundNo").asText(""),
                settlementNode.asLong(),
                envelope.payload().path("patientId").asLong(0L),
                envelope.payload().path("amount").asLong(0L),
                envelope.payload().path("refundType").asText(""),
                envelope.payload().path("autoApproved").asBoolean(false));
        appointmentService.confirmRefundedCancel(payload);
        // order 分支（Task 10，appointment 分支之后追加）：按结算锚反查来源单据驱动开单退费逆向扇出
        chargingService.onRefundApproved(payload);
    }
}

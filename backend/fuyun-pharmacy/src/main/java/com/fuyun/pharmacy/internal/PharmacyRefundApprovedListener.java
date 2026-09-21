package com.fuyun.pharmacy.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispenseService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 退费审批回执消费侧（billing.refund.approved，V605 id 20 既有登记——CF-4 载荷零变更）：
 * 以 M13 回执为退费权威，经 billing SettlementQueryPort 按结算单反查处方号精确清单
 * （sourceRefsOfSettlement(settlementId).rxRefs()，裁决 5 单据化——PR-5 注记⑦误伤面收口交付），
 * 驱动已受理退药终态的发药单向处方镜像终态（DISPENSED→PART/FULL_RETURNED，Spec :132）。
 * 反查空清单=该结算无药品费用行（纯检查/检验退费）info 跳过。
 *
 * <p>双通道终态收敛说明：本消费者（refund.approved）与 order.cancelled 双通道依赖 eventId
 * 构件幂等（IdempotentConsumerSupport 去重）+ 业务级终态重读跳过
 * （DispenseServiceImpl.confirmRefundTerminalByRx 已终态不再迁移）保证重复到达只收敛一次。
 * 归 internal/，Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyRefundApprovedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispenseService dispenseService;

    /** 结算单反查端口（处方号精确清单唯一载体，billing api 只读面），非空 */
    private final com.fuyun.billing.api.SettlementQueryPort settlementQueryPort;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑；
     * Task 11 起扩三参——settlementQueryPort 承载反查）。
     *
     * @param consumerSupport     消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param dispenseService     发药服务，非空
     * @param settlementQueryPort 结算单反查端口，非空
     */
    public PharmacyRefundApprovedListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispenseService dispenseService,
            com.fuyun.billing.api.SettlementQueryPort settlementQueryPort) {
        this.consumerSupport = consumerSupport;
        this.dispenseService = dispenseService;
        this.settlementQueryPort = settlementQueryPort;
    }

    /**
     * 退费审批通过事件入口。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_BILLING_REFUND_APPROVED)
    public void onRefundApproved(Message message) {
        consumerSupport.consume(message, this::handleRefundApproved);
    }

    /**
     * 退费审批通过业务体（包级直驱可测）：载荷读 settlementId（V605 id 20 冻结契约）反查
     * 处方清单后传递单据化收敛。
     *
     * @param envelope 事件信封，非空；载荷契约 CF-4 冻结（refundId/refundNo/settlementId/
     *                 patientId/amount/refundType/autoApproved）
     * @throws IllegalStateException 缺 settlementId（不合规帧禁静默吞）
     */
    void handleRefundApproved(EventEnvelope envelope) {
        // settlementId 以数值承载，0 兜底值即不合规帧显式抛出进死信留痕
        long settlementId = envelope.payload().path("settlementId").asLong(0L);
        if (settlementId == 0L) {
            throw new IllegalStateException(
                    "退费审批回执载荷不合规（缺 settlementId）：eventType=" + envelope.eventType() + "，payload=" + envelope.payload());
        }
        // 单据化反查（裁决 5）：CF-4 载荷零变更，处方清单经 billing api 端口按结算单反查承载
        List<String> rxNos =
                settlementQueryPort.sourceRefsOfSettlement(settlementId).rxRefs();
        if (rxNos.isEmpty()) {
            log.info(
                    "退费终态收敛跳过（该结算无药品费用行）：settlementId={}，refundNo={}",
                    settlementId,
                    envelope.payload().path("refundNo").asText(null));
            return;
        }
        dispenseService.confirmRefundTerminalByRx(rxNos);
    }
}

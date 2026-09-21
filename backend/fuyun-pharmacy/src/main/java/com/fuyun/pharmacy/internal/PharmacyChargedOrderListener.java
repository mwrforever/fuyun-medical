package com.fuyun.pharmacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispenseService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 门诊放行事件消费侧（outpatient.order.charged，V204 id 25 冻结载荷——V702 占位经 UPDATE 升级）：
 * 按 payload rxNos 精确清单驱动门诊/急诊处方转 PENDING_DISPENSE 的唯一权威放行通道（Spec §3.1，
 * 裁决 4 单据精确放行——PR-5 回切交付）。载荷守卫：visitId 与 rxNos 双字段不合规帧显式抛出进
 * 死信留痕；rxNos 空数组=该结算无药品行（纯检查/检验结算）合法 info 跳过。
 *
 * <p>幂等边界：eventId 构件幂等 + 业务级 CAS 重读定性（DispenseServiceImpl.releaseByRxNos）；
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
        consumerSupport.consume(message, this::handleOrderCharged);
    }

    /**
     * 缴费放行业务体（包级直驱可测，PharmacyMasterDataListener 同款形态）：载荷读 rxNos 数组
     * 传递单据精确放行清单。
     *
     * @param envelope 事件信封，非空；载荷契约 V204 id 25 冻结（settlementId/settleNo/patientId/
     *                 visitId/orderNos/rxNos/greenChannelFlag）
     * @throws IllegalStateException 缺 visitId 或 rxNos 非数组（不合规帧禁静默吞）
     */
    void handleOrderCharged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = payload.path("visitId").asText(null);
        JsonNode rxNosNode = payload.path("rxNos");
        if (visitId == null || visitId.isBlank() || !rxNosNode.isArray()) {
            // 冻结契约不合规帧显式暴露（V204 id 25：visitId/rxNos 为本消费方必读双字段）
            throw new IllegalStateException(
                    "缴费放行事件载荷不合规（缺 visitId 或 rxNos 非数组）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        List<String> rxNos = new ArrayList<>();
        for (JsonNode rxNo : rxNosNode) {
            rxNos.add(rxNo.asText());
        }
        if (rxNos.isEmpty()) {
            // 空清单=该结算无药品行（纯检查/检验结算），合法帧 info 跳过（裁决 4：放行无对象）
            log.info(
                    "缴费放行跳过（该结算无药品行）：visitId={}，settleNo={}",
                    visitId,
                    payload.path("settleNo").asText(null));
            return;
        }
        dispenseService.releaseByRxNos(rxNos);
    }
}

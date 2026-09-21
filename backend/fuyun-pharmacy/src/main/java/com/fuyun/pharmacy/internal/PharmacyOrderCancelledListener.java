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
 * 门诊退费逆向消费侧（outpatient.order.cancelled，V204 id 31 冻结载荷——V702 占位经 UPDATE 升级，
 * PR-5 注记⑥回切实装）：按 payload rxNos 清单驱动未发药处方作废终态确认（PENDING_DISPENSE/
 * DISPENSING→CANCELLED+发药单退场+批次锁释放，Spec :132/:134；已发药终态归 refund.approved
 * 双通道收敛，不再承担退药指令——Spec :209 B-3 单向链）。载荷守卫：rxNos 非数组不合规帧显式
 * 抛出进死信留痕；空数组=退费结算无药品处方（纯检查/检验退费）合法 info 跳过。
 *
 * <p>幂等边界：eventId 构件幂等（IdempotentConsumerSupport 去重）+ 业务级终态重读跳过
 * （DispenseServiceImpl.voidUndispensedByRx 已作废/已终态零写直返）。归 internal/，
 * Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class PharmacyOrderCancelledListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispenseService dispenseService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑；
     * Task 11 实装起扩二参——dispenseService 承载未发药作废）。
     *
     * @param consumerSupport 消费模板，非空；定绑 pharmacyConsumerSupport Bean
     * @param dispenseService 发药服务，非空
     */
    public PharmacyOrderCancelledListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispenseService dispenseService) {
        this.consumerSupport = consumerSupport;
        this.dispenseService = dispenseService;
    }

    /**
     * 门诊退费逆向事件入口。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + PharmacyMessagingConstants.MODULE + "."
                    + PharmacyMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CANCELLED)
    public void onOrderCancelled(Message message) {
        consumerSupport.consume(message, this::handleOrderCancelled);
    }

    /**
     * 门诊退费逆向业务体（包级直驱可测）：payload 读 rxNos[]（V204 id 31 冻结契约）与 reason
     * 透传，驱动未发药处方作废。
     *
     * @param envelope 事件信封，非空；载荷契约 V204 id 31 冻结（orderNo/patientId/visitId/
     *                 rxNos/reason）
     * @throws IllegalStateException rxNos 非数组（不合规帧禁静默吞）
     */
    void handleOrderCancelled(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        JsonNode rxNosNode = payload.path("rxNos");
        if (!rxNosNode.isArray()) {
            throw new IllegalStateException(
                    "门诊退费逆向事件载荷不合规（缺 rxNos 数组）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        List<String> rxNos = new ArrayList<>();
        for (JsonNode rxNo : rxNosNode) {
            rxNos.add(rxNo.asText());
        }
        if (rxNos.isEmpty()) {
            log.info("退费逆向终态确认跳过（无药品处方清单）：orderNo={}", payload.path("orderNo").asText(null));
            return;
        }
        dispenseService.voidUndispensedByRx(rxNos, payload.path("reason").asText(null));
    }
}

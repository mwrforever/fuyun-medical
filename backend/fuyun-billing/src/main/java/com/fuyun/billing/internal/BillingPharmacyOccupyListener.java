package com.fuyun.billing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * M06 发药/退药占用回写消费侧（13-billing Spec §7「执行占用回写」PR-4 兑现）：发药完成标记
 * 费用行组 exec_occupy_status=DISPENSED（退费硬前置 BILL-1017 生效），全额退药回退 NONE
 * （解锁收费窗口退费）；部分退保持 DISPENSED（余量仍在患者侧，全退前不得退费）。
 * 幂等=CAS 谓词（0 行即达成）；载荷以 JsonNode 读（禁依赖 pharmacy api——模块依赖单向）。
 * 归 internal/；Bean 注册点 BillingMessagingConfig @Import。
 */
@Slf4j
public class BillingPharmacyOccupyListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final FeeRecordMapper feeRecordMapper;

    /**
     * 全参构造器（装配归 BillingMessagingConfig @Import；消费模板 @Qualifier 定绑）。
     *
     * @param consumerSupport 消费模板，非空；定绑 billingConsumerSupport Bean
     * @param feeRecordMapper 费用行 mapper（CAS 回写），非空
     */
    public BillingPharmacyOccupyListener(
            @Qualifier("billingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            FeeRecordMapper feeRecordMapper) {
        this.consumerSupport = consumerSupport;
        this.feeRecordMapper = feeRecordMapper;
    }

    /**
     * 发药完成入口（q.billing.pharmacy.dispense.completed）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + BillingMessagingConstants.MODULE + "."
                    + BillingMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_COMPLETED)
    public void onDispenseCompleted(Message message) {
        consumerSupport.consume(message, this::handleCompleted);
    }

    /**
     * 退药受理入口（q.billing.pharmacy.dispense.returned）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + BillingMessagingConstants.MODULE + "."
                    + BillingMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_RETURNED)
    public void onDispenseReturned(Message message) {
        consumerSupport.consume(message, this::handleReturned);
    }

    /** 业务体：NONE→DISPENSED 条件回写（缺 rxNo 即不合规帧抛出进死信留痕） */
    void handleCompleted(EventEnvelope envelope) {
        String rxNo = requireRxNo(envelope);
        int rows = feeRecordMapper.casMarkDispensed(rxNo);
        log.info("发药占用回写：rxNo={}，DISPENSED 行数={}", rxNo, rows);
    }

    /** 业务体：fullReturn=true 回退 NONE；false 保持 DISPENSED（部分退余量仍在患者侧） */
    void handleReturned(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String rxNo = requireRxNo(envelope);
        if (payload.path("fullReturn").asBoolean(false)) {
            int rows = feeRecordMapper.casReleaseDispense(rxNo);
            log.info("退药占用回退：rxNo={}，NONE 行数={}（退费硬前置解锁）", rxNo, rows);
        } else {
            log.info("退药部分退保持占用：rxNo={}（全退前退费维持 BILL-1017 拦截）", rxNo);
        }
    }

    /** 载荷 rxNo 守卫（prescriptionId 同值锚点二选一，优先 rxNo 显式字段） */
    private static String requireRxNo(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String rxNo = payload.path("rxNo").asText(null);
        if (rxNo == null || rxNo.isBlank()) {
            rxNo = payload.path("prescriptionId").asText(null);
        }
        if (rxNo == null || rxNo.isBlank()) {
            throw new IllegalStateException(
                    "占用回写载荷不合规（缺 rxNo/prescriptionId）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return rxNo;
    }
}

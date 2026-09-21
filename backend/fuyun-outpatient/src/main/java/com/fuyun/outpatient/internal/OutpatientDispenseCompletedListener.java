package com.fuyun.outpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IChargingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 发药完成回流消费侧（pharmacy.dispense.completed，V702 id 28 既有登记；「已发药」派生镜像
 * 唯一回流通道）：RX_REF 引用行 dispense_status=DISPENSED 镜像（引用行状态机五值不变，Spec :142
 * 医生站/患者端可见已发药）业务分发面。载荷锚守卫归 IChargingService.onDispenseCompleted，本
 * 监听器仅做编排所消费组件（rxNo/dispenseNo；lines 组件归 M13 占用面，本编排不消费不解析）解析
 * 与三段式委托。归 internal/，Bean 注册点 OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientDispenseCompletedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IChargingService chargingService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport 消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param chargingService 收费编排服务，非空；发药回流业务体（已发药镜像写面）
     */
    public OutpatientDispenseCompletedListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IChargingService chargingService) {
        this.consumerSupport = consumerSupport;
        this.chargingService = chargingService;
    }

    /**
     * 发药完成回流事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_COMPLETED)
    public void onDispenseCompleted(Message message) {
        consumerSupport.consume(message, this::handleDispenseCompleted);
    }

    /**
     * 回流业务体（包级可见供单测直驱；@RabbitListener 入口仅做 consume 委托）：V702 id 28 载荷
     * 所消费组件解析后委托收费编排。
     *
     * @param envelope 已解码信封，非空
     */
    void handleDispenseCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        chargingService.onDispenseCompleted(
                payload.path("rxNo").asText(""), payload.path("dispenseNo").asText(""));
    }
}

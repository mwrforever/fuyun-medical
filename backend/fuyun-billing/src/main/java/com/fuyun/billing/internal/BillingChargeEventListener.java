package com.fuyun.billing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 门诊开单 / 处方生效事件消费侧（CF-5 占位订阅、计价引擎事件驱动入口，Spec §3.1「后计费」分流）。
 *
 * <p>消费姿态：raw Message + common {@link IdempotentConsumerSupport} 标准三段式（终审 Minor 范式
 * 收敛的 billing 落点）；队列经 BillingMessagingConfig 治理构件声明（q.billing.&lt;事件三段名&gt;，
 * 先登记后订阅——V605 id 23–24）。逐行展开经 generateFromSource，billing_key 唯一约束兜底防重。
 *
 * <p>幂等边界：BILL-1009（同源单同项目同日已计费）判为幂等达成、跳过不再上抛——重投/补偿重发
 * 帧不因重复进死信；其余业务异常（缺项/定价不可得）原样上抛走容器有界重试→fy.dlx，
 * 由「费用↔来源单据一致性对账」（Spec §10）人工核查兜底。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 BillingMessagingConfig @Import。
 */
@Slf4j
public class BillingChargeEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IPricingEngineService engine;

    /**
     * 全参构造器（装配归 BillingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，fuyun-app 上下文双候选，必须 @Qualifier 定绑 billingConsumerSupport——
     * 第 2 轮审查 P0-1，Global Constraints common 模板类红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 billingConsumerSupport Bean
     * @param engine          计价引擎（本模块 api 面消费），非空
     */
    public BillingChargeEventListener(
            @Qualifier("billingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IPricingEngineService engine) {
        this.consumerSupport = consumerSupport;
        this.engine = engine;
    }

    /**
     * 门诊开单事件入口（非药品计费行；CF-5 占位契约，PR-5 实装冻结后字段零改动接通）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + BillingMessagingConstants.MODULE + "."
                    + BillingMessagingConstants.EVENT_SUB_OUTPATIENT_ORDER_CREATED)
    public void onOutpatientOrderCreated(Message message) {
        consumerSupport.consume(message, envelope -> generateLines(envelope, TriggerType.ORDER_CONFIRMED, "orderId"));
    }

    /**
     * 处方生效事件入口（药品计费行，M-4 裁决统一权威时点）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q."
                    + BillingMessagingConstants.MODULE
                    + "."
                    + BillingMessagingConstants.EVENT_SUB_PHARMACY_PRESCRIPTION_CREATED)
    public void onPrescriptionCreated(Message message) {
        consumerSupport.consume(
                message, envelope -> generateLines(envelope, TriggerType.PRESCRIPTION_EFFECTIVE, "prescriptionId"));
    }

    /**
     * 载荷逐行展开为计费命令（一单多行；行级异常按幂等边界分流）。
     *
     * @param envelope       已解析信封，非空
     * @param trigger        计费点触发型（两事件分流）
     * @param sourceRefField 来源单据号字段名（orderId / prescriptionId）
     * @throws IllegalStateException 载荷缺三要素或 lines 空/非数组（CF-5 占位契约不合规帧）
     */
    private void generateLines(EventEnvelope envelope, TriggerType trigger, String sourceRefField) {
        JsonNode payload = envelope.payload();
        String sourceRef = payload.path(sourceRefField).asText(null);
        long patientId = payload.path("patientId").asLong(0L);
        String visitId = payload.path("visitId").asText(null);
        JsonNode lines = payload.path("lines");
        if (sourceRef == null || patientId == 0L || visitId == null || !lines.isArray() || lines.isEmpty()) {
            throw new IllegalStateException(
                    "开单事件载荷不合规（CF-5 占位契约）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        for (JsonNode line : lines) {
            FeeGenerateCommand cmd = new FeeGenerateCommand(
                    patientId,
                    visitId,
                    ChargeSource.ORDER_LINKED,
                    sourceRef,
                    trigger,
                    line.path("itemCode").asText(),
                    new BigDecimal(line.path("quantity").asText("0")),
                    VisitType.OUT,
                    null,
                    null);
            try {
                long feeId = engine.generateFromSource(cmd);
                log.info(
                        "开单事件计费成功：eventType={}，sourceRef={}，item={}，feeId={}",
                        envelope.eventType(),
                        sourceRef,
                        cmd.itemCode(),
                        feeId);
            } catch (BizException e) {
                if (e.getErrorCode() == BillingErrorCode.DUPLICATE_CHARGING) {
                    // 重复计费幂等达成：跳过本行（部分行失败重试后，已生成行重投即走此分支）
                    log.warn("开单事件重复计费幂等跳过：sourceRef={}，item={}", sourceRef, cmd.itemCode());
                    continue;
                }
                throw e; // 其余业务失败上抛：有界重试耗尽进 fy.dlx 留痕，禁静默丢费
            }
        }
    }
}

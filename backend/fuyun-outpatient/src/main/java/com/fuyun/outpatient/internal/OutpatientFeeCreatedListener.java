package com.fuyun.outpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.service.IClinicOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 费用生成回执消费侧（billing.fee.created，V605 id 17 + V1115 契约演进；申请单 CREATED→PENDING_FEE
 * 与 visit 待缴费推进唯一驱动源）：billingKey 为 M13 计费唯一键五段（patientId|sourceRef|trigger|
 * itemId|billingDate，PricingEngineServiceImpl 同构），仅处理开单计费点（trigger=ORDER_CONFIRMED）
 * ——其余计费点（处方生效/手工等）非本监听器职责，info 跳过；sourceRef 段即申请单号（order.created
 * 载荷 orderId 直取），业务体委托 IClinicOrderService.markPendingFee（重投幂等=单据 CAS 0 行重读
 * PENDING_FEE info 跳过）。住院行（visitType=IN，V1115 追加组件）跳过申请单推进——sourceRef 语义
 * 双载（门诊申请单号 vs 住院医嘱号）死信终结（W-67b）。归 internal/，Bean 注册点
 * OutpatientMessagingConfig @Import。
 */
@Slf4j
public class OutpatientFeeCreatedListener {

    /** 开单计费点 trigger 值（billing TriggerType.ORDER_CONFIRMED code，开单事件计费唯一处理口径） */
    private static final String TRIGGER_ORDER_CONFIRMED = "ORDER_CONFIRMED";

    /** billingKey 分段数（五段：patientId|sourceRef|trigger|itemId|billingDate，V602 唯一键口径） */
    private static final int BILLING_KEY_SEGMENTS = 5;

    /** billingKey trigger 段下标（第三段） */
    private static final int TRIGGER_SEGMENT_INDEX = 2;

    /** billingKey sourceRef 段下标（第二段，即申请单号） */
    private static final int SOURCE_REF_SEGMENT_INDEX = 1;

    private final IdempotentConsumerSupport consumerSupport;

    private final IClinicOrderService clinicOrderService;

    /**
     * 全参构造器（装配归 OutpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * ——Global Constraints common 模板类多实例红线）。
     *
     * @param consumerSupport    消费模板，非空；定绑 outpatientConsumerSupport Bean
     * @param clinicOrderService 开单服务，非空；缴费回执业务体（单据/visit 待缴费推进）
     */
    public OutpatientFeeCreatedListener(
            @Qualifier("outpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IClinicOrderService clinicOrderService) {
        this.consumerSupport = consumerSupport;
        this.clinicOrderService = clinicOrderService;
    }

    /**
     * 费用生成回执事件入口（标准三段式：NX 去重 → 业务 → PROCESSED 登记）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + OutpatientMessagingConstants.MODULE + "."
                    + OutpatientMessagingConstants.EVENT_SUB_BILLING_FEE_CREATED)
    public void onFeeCreated(Message message) {
        consumerSupport.consume(message, this::handleFeeCreated);
    }

    /**
     * 回执业务体（包级可见供单测直驱；@RabbitListener 入口仅做 consume 委托）：billingKey 五段
     * 解析、住院行 visitType=IN 跳过（W-67b）与开单计费点守卫，sourceRef=申请单号委托
     * markPendingFee。
     *
     * @param envelope 已解码信封，非空
     * @throws IllegalStateException billingKey 缺失或非五段（不合规帧）时触发——死信留痕禁静默丢弃
     */
    void handleFeeCreated(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String billingKey = payload.path("billingKey").asText("");
        String[] segments = billingKey.split("\\|");
        // billingKey 守卫：缺失或段数不符即不合规帧显式抛出（对账锚点损坏，人工核查兜底）
        // EX-19 C 类收口留痕：MQ 回执载荷守卫（内部事件契约违例，非用户输入路径），保留 ISE 进死信留痕
        if (billingKey.isBlank() || segments.length != BILLING_KEY_SEGMENTS) {
            throw new IllegalStateException(
                    "缴费回执载荷不合规（billingKey 非五段）：eventType=" + envelope.eventType() + "，billingKey=" + billingKey);
        }
        // W-67b：住院行跳过——住院计费的缴费回执不推进门诊申请单（markPendingFee 查无 clinic_order
        // 的死信根因即 sourceRef 语义双载：门诊申请单号 vs 住院医嘱号；visitType 组件终结双载）。
        // 旧版本事件帧无 visitType 组件——asText("") 兜底空串≠"IN" 走原路径，兼容不破坏
        if ("IN".equals(payload.path("visitType").asText(""))) {
            log.info(
                    "fee.created 住院行跳过门诊申请单推进：feeNo={}，sourceRef={}",
                    payload.path("feeNo").asText(""),
                    segments[SOURCE_REF_SEGMENT_INDEX]);
            return;
        }
        // 开单计费点守卫：非 ORDER_CONFIRMED 触发（处方生效/手工计费等）非本监听器职责，info 跳过
        String trigger = segments[TRIGGER_SEGMENT_INDEX];
        if (!TRIGGER_ORDER_CONFIRMED.equals(trigger)) {
            log.info("缴费回执非开单计费点，跳过：trigger={}，eventType={}，billingKey={}", trigger, envelope.eventType(), billingKey);
            return;
        }
        String orderNo = segments[SOURCE_REF_SEGMENT_INDEX];
        clinicOrderService.markPendingFee(orderNo);
    }
}

package com.fuyun.pharmacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispensePlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 住院终清事件消费侧（M06 住院摆药域，P2 PR-3 Task 8）：inpatient.order.stopped /
 * inpatient.visit.discharged 两路作废——未摆药计划（CREATED/PICKING）CANCELLED（原因留痕）；
 * 出院路另提示已摆未用（PICKED/CHECKED/DELIVERED）计划退药人工发起（不自动回补）。
 * 队列名由治理构件按 q.pharmacy.&lt;登记名&gt; 统一推导（PharmacyMessagingConfig
 * pharmacyConsumerQueues，V800 id 44/51 登记面既有——nursing 侧已消费同族先例）。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear（审计列落 SYSTEM 文本，Task 5/6 四监听器同款先例）。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式）+ 业务级幂等
 * （casCancel 状态谓词——已终态 0 行幂等跳过，消费位纪律定性跳过不上抛）。载荷以
 * JsonNode 读（禁依赖 inpatient api——模块依赖单向红线，MedicationOrderReviewListener
 * 同款口径）。载荷字段缺失抛 ISE 进死信留痕（不合规帧禁静默吞）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 PharmacyMessagingConfig @Import。
 */
@Slf4j
public class InpatientTerminalEventListener {

    /** 系统触发面操作者（GC15 桥接口径——与 nursing 侧 SYSTEM 白名单字面量同源） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    private final IdempotentConsumerSupport consumerSupport;

    private final IDispensePlanService dispensePlanService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，必须 @Qualifier 定绑 pharmacyConsumerSupport——common 模板类多实例红线；
     * 单测按位置构造零改动）。
     *
     * @param consumerSupport     消费模板，非空；定绑 PharmacyMessagingConfig pharmacyConsumerSupport Bean
     * @param dispensePlanService 住院摆药计划服务（终清作废业务体），非空
     */
    public InpatientTerminalEventListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IDispensePlanService dispensePlanService) {
        this.consumerSupport = consumerSupport;
        this.dispensePlanService = dispensePlanService;
    }

    /**
     * 医嘱停止消费入口（q.pharmacy.inpatient.order.stopped，V800 id 44）：该医嘱未摆药计划作废。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = PharmacyMessagingConstants.CONSUMER_QUEUE_PREFIX
                    + PharmacyMessagingConstants.MODULE
                    + "."
                    + PharmacyMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED)
    public void onOrderStopped(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleOrderStopped);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 出院终清消费入口（q.pharmacy.inpatient.visit.discharged，V800 id 51）：该就诊未摆药计划
     * 作废 + 已摆未用提示（退药人工发起）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = PharmacyMessagingConstants.CONSUMER_QUEUE_PREFIX
                    + PharmacyMessagingConstants.MODULE
                    + "."
                    + PharmacyMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGED)
    public void onVisitDischarged(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleVisitDischarged);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 停嘱业务体（包级直驱可测）：载荷读 m04OrderNo/stopReason（V800 id 44 冻结契约子集，
     * nursing InpatientOrderEventListener 同族读面）透传作废域。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 44 冻结
     * @throws IllegalStateException 缺 m04OrderNo/stopReason（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleOrderStopped(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String m04OrderNo = requireText(envelope, payload, "m04OrderNo");
        String stopReason = requireText(envelope, payload, "stopReason");
        log.info(
                "医嘱停止事件消费（住院摆药作废）：eventType={}，m04OrderNo={}，stopReason={}",
                envelope.eventType(),
                m04OrderNo,
                stopReason);
        dispensePlanService.cancelByOrderTerminal(m04OrderNo, stopReason);
    }

    /**
     * 出院业务体（包级直驱可测）：载荷读 visitId/patientId/dischargedAt（V800 id 51 冻结契约
     * 子集；作废定位仅需 visitId，余字段日志留痕）透传终清域。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 51 冻结
     * @throws IllegalStateException 缺 visitId/patientId/dischargedAt（死信留痕）时触发
     */
    void handleVisitDischarged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "出院终清载荷不合规（patientId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        String dischargedAt = requireText(envelope, payload, "dischargedAt");
        log.info(
                "出院终清事件消费（住院摆药作废）：eventType={}，visitId={}，patientId={}，dischargedAt={}",
                envelope.eventType(),
                visitId,
                patientId,
                dischargedAt);
        dispensePlanService.cancelByVisitDischarge(visitId);
    }

    /**
     * 载荷必填文本守卫（缺失/空白即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 字段文本值，非空
     */
    private static String requireText(EventEnvelope envelope, JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "住院终清载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }
}

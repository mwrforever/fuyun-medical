package com.fuyun.nursing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.service.IOrderExecutionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 住院医嘱事件族消费侧（M05 执行单生成域，Task 4）：inpatient.order.transferred /
 * order-plan.generated / stopped / cancelled 四路合一——前两路驱动执行单生成（临时单/
 * 计划批量单），后两路驱动未执行单撤销（停嘱/作废）。队列名由治理构件按
 * q.nursing.&lt;登记名&gt; 统一推导（NursingMessagingConfig nursingConsumerQueues，V800
 * id 42–45 登记面既有）。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式，键=eventId+消费模块）+
 * 业务级幂等（uk_execution_plan ON CONFLICT DO NOTHING / CAS 未执行态谓词——服务层承载）。
 * 载荷以 JsonNode 读（禁依赖 inpatient api——GC11 模块依赖单向红线，MedicationOrderReviewListener
 * 同款口径）。载荷字段缺失/形态违约抛 ISE 进死信留痕（不合规帧禁静默吞）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class InpatientOrderEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IOrderExecutionService orderExecutionService;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 nursingConsumerSupport——GC7 common
     * 模板类多实例红线；单测按位置构造零改动）。
     *
     * @param consumerSupport       消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param orderExecutionService 执行单生成域服务（三方法业务体），非空
     */
    public InpatientOrderEventListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IOrderExecutionService orderExecutionService) {
        this.consumerSupport = consumerSupport;
        this.orderExecutionService = orderExecutionService;
    }

    /**
     * 医嘱转抄消费入口（q.nursing.inpatient.order.transferred，V800 id 42）：类型快照+临时单生成。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_TRANSFERRED)
    public void onOrderTransferred(Message message) {
        consumerSupport.consume(message, this::handleOrderTransferred);
    }

    /**
     * 计划拆分消费入口（q.nursing.inpatient.order-plan.generated，V800 id 43）：次日/当日执行单批量生成。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED)
    public void onOrderPlanGenerated(Message message) {
        consumerSupport.consume(message, this::handleOrderPlanGenerated);
    }

    /**
     * 医嘱停止消费入口（q.nursing.inpatient.order.stopped，V800 id 44）：未执行执行单撤销。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED)
    public void onOrderStopped(Message message) {
        consumerSupport.consume(message, this::handleOrderStopped);
    }

    /**
     * 医嘱作废消费入口（q.nursing.inpatient.order.cancelled，V800 id 45）：未执行执行单撤销。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_CANCELLED)
    public void onOrderCancelled(Message message) {
        consumerSupport.consume(message, this::handleOrderCancelled);
    }

    /**
     * 转抄业务体（包级直驱可测）：载荷读 m04OrderNo/visitId/patientId/transferType/
     * firstTransferredAt（V800 id 42 冻结契约子集）透传生成域。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 42 冻结
     * @throws IllegalStateException 缺任一冻结字段（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleOrderTransferred(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        log.info(
                "医嘱转抄事件消费：eventType={}，m04OrderNo={}，visitId={}",
                envelope.eventType(),
                payload.path("m04OrderNo").asText(),
                payload.path("visitId").asText());
        orderExecutionService.onOrderTransferred(
                requireText(envelope, payload, "m04OrderNo"),
                requireText(envelope, payload, "visitId"),
                requirePatientId(envelope, payload),
                requireText(envelope, payload, "transferType"),
                requireInstant(envelope, payload, "firstTransferredAt"));
    }

    /**
     * 计划拆分业务体（包级直驱可测）：载荷读 m04OrderNo/visitId/patientId/planDate/planNos[]/
     * planTimes[]（V800 id 43 冻结契约子集）。双数组须为数组形态且下标对齐（planTime 组合
     * 不可推测）——违约即不合规帧死信留痕；空数组合法（无计划面，服务侧零循环直返）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 43 冻结
     * @throws IllegalStateException 缺定位键、planDate 非法、数组形态/对齐违约（死信留痕）时触发
     */
    void handleOrderPlanGenerated(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        LocalDate planDate = requireLocalDate(envelope, payload, "planDate");
        List<String> planNos = requireTextArray(envelope, payload, "planNos");
        List<String> planTimes = requireTextArray(envelope, payload, "planTimes");
        if (planNos.size() != planTimes.size()) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException("计划拆分载荷不合规（planNos/planTimes 下标对齐违约）：eventType="
                    + envelope.eventType() + "，planNos.size()=" + planNos.size()
                    + "，planTimes.size()=" + planTimes.size());
        }
        log.info(
                "医嘱计划拆分事件消费：eventType={}，m04OrderNo={}，visitId={}，planDate={}，planCount={}",
                envelope.eventType(),
                payload.path("m04OrderNo").asText(),
                payload.path("visitId").asText(),
                planDate,
                planNos.size());
        orderExecutionService.onPlanGenerated(
                requireText(envelope, payload, "m04OrderNo"),
                requireText(envelope, payload, "visitId"),
                requirePatientId(envelope, payload),
                planDate,
                planNos,
                planTimes);
    }

    /**
     * 停嘱业务体（包级直驱可测）：载荷读 m04OrderNo/stopReason（V800 id 44 冻结契约子集；
     * 停嘱时点与操作者由 M04 台账承载，本侧撤销原因留痕即可）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 44 冻结
     * @throws IllegalStateException 缺 m04OrderNo/stopReason（死信留痕）时触发
     */
    void handleOrderStopped(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String m04OrderNo = requireText(envelope, payload, "m04OrderNo");
        String stopReason = requireText(envelope, payload, "stopReason");
        log.info("医嘱停止事件消费：eventType={}，m04OrderNo={}，stopReason={}", envelope.eventType(), m04OrderNo, stopReason);
        orderExecutionService.onOrderTerminal(m04OrderNo, stopReason, IOrderExecutionService.TerminalKind.STOPPED);
    }

    /**
     * 作废业务体（包级直驱可测）：载荷读 m04OrderNo/cancelReason（V800 id 45 冻结契约子集），
     * 撤销面与停嘱同款。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 45 冻结
     * @throws IllegalStateException 缺 m04OrderNo/cancelReason（死信留痕）时触发
     */
    void handleOrderCancelled(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String m04OrderNo = requireText(envelope, payload, "m04OrderNo");
        String cancelReason = requireText(envelope, payload, "cancelReason");
        log.info("医嘱作废事件消费：eventType={}，m04OrderNo={}，cancelReason={}", envelope.eventType(), m04OrderNo, cancelReason);
        orderExecutionService.onOrderTerminal(m04OrderNo, cancelReason, IOrderExecutionService.TerminalKind.CANCELLED);
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
                    "医嘱事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }

    /**
     * 载荷 patientId 守卫（缺失/非法即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 患者主索引，非空
     */
    private static long requirePatientId(EventEnvelope envelope, JsonNode payload) {
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "医嘱事件载荷不合规（patientId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return patientId;
    }

    /**
     * 载荷必填时点守卫（ISO-8601 Instant 文本；缺失/不可解析即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 时点，非空
     */
    private static Instant requireInstant(EventEnvelope envelope, JsonNode payload, String field) {
        String value = requireText(envelope, payload, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "医嘱事件载荷不合规（" + field + " 非法时点文本）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
    }

    /**
     * 载荷必填计划日期守卫（ISO yyyy-MM-dd；缺失/不可解析即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 计划日期，非空
     */
    private static LocalDate requireLocalDate(EventEnvelope envelope, JsonNode payload, String field) {
        String value = requireText(envelope, payload, field);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "医嘱事件载荷不合规（" + field + " 非法日期文本）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
    }

    /**
     * 载荷必填文本数组守卫（非数组形态即不合规帧；空数组合法——无计划面）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 文本值清单（保序），非空
     */
    private static List<String> requireTextArray(EventEnvelope envelope, JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        if (!node.isArray()) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "医嘱事件载荷不合规（" + field + " 非数组）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }
}

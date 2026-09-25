package com.fuyun.billing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.service.IInpatientChargeService;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 住院域事件消费侧（M13 住院计费联动，P2 PR-1 Task 13）：inpatient.visit.# 与
 * inpatient.order.# 两通配队列（自声明绑定，governance 订阅登记精确匹配无法承载通配段——
 * Task 12 drug 子键队列自声明同款先例），按信封 eventType 派发六事件业务体。
 *
 * <p>冻结面适配留痕（brief「audited 通配离散计价」×V800 id 41 冻结载荷的字面差异）：audited
 * 载荷六字段（m04OrderNo/visitId/patientId/auditType/auditOperator/auditedAt）不携 items，
 * 计价数据面不可得；离散计价唯一可承载载荷为 inpatient.order.created（V901 id 66 items[]），
 * 且门诊侧先例即开单事件驱动计价（BillingChargeEventListener 消费 outpatient.order.created）——
 * 故 PENDING 计价落行随 created 承载，audited 帧到店仅 info 留痕直返（PENDING 行经 executed
 * 确认、经 stopped 截断，审核结论不改计费状态面；Task 16 联调 IT 观测终态不受影响）。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式，键=eventId+消费模块）+
 * 业务级幂等（锚点/停费标记存在性守卫、计费唯一键数据库硬防重——服务层承载）。载荷以 JsonNode
 * 读（禁依赖 inpatient api——GC11 模块依赖单向红线，PharmacyAuditReplyListener 同款口径）。
 * 通配队列还承载六事件与三终态事件之外的 visit/order 族帧（registered/discharged/
 * transferred-order/order-plan.generated 等），未知类型 info 留痕直返（消费面零误伤）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 BillingMessagingConfig @Import。
 */
@Slf4j
public class BillingInpatientEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IInpatientChargeService inpatientChargeService;

    /**
     * 全参构造器（装配归 BillingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，fuyun-app 上下文双候选，必须 @Qualifier 定绑 billingConsumerSupport——
     * GC7 common 模板类多实例红线；单测按位置构造零改动）。
     *
     * @param consumerSupport        消费模板，非空；定绑 BillingMessagingConfig billingConsumerSupport Bean
     * @param inpatientChargeService 住院计费联动服务（六事件业务体），非空
     */
    public BillingInpatientEventListener(
            @Qualifier("billingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IInpatientChargeService inpatientChargeService) {
        this.consumerSupport = consumerSupport;
        this.inpatientChargeService = inpatientChargeService;
    }

    /**
     * 住院就诊域通配入口（q.billing.inpatient.visit.#；admitted/transferred/discharge-requested
     * 三事件派发，其余 visit 族帧 info 直返）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = BillingMessagingConstants.CONSUMER_QUEUE_PREFIX
                    + BillingMessagingConstants.MODULE
                    + "."
                    + BillingMessagingConstants.BINDING_KEY_INPATIENT_VISIT_ALL)
    public void onVisitEvent(Message message) {
        consumerSupport.consume(message, this::handleVisitEvent);
    }

    /**
     * 住院医嘱域通配入口（q.billing.inpatient.order.#；created 离散计价/executed 确认/stopped+
     * cancelled/revoked/audit-rejected 终态截断派发，其余 order 族帧——含 audited 冻结载荷无业务
     * 动作——info 直返）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = BillingMessagingConstants.CONSUMER_QUEUE_PREFIX
                    + BillingMessagingConstants.MODULE
                    + "."
                    + BillingMessagingConstants.BINDING_KEY_INPATIENT_ORDER_ALL)
    public void onOrderEvent(Message message) {
        consumerSupport.consume(message, this::handleOrderEvent);
    }

    /**
     * 就诊域业务体派发（包级直驱可测）：eventType 精确匹配三登记名，未纳管类型零业务触达。
     *
     * @param envelope 已解析信封，非空
     * @throws IllegalStateException 载荷缺定位键（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleVisitEvent(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String eventType = envelope.eventType();
        switch (eventType) {
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_ADMITTED ->
                inpatientChargeService.onVisitAdmitted(
                        requireVisitId(envelope, payload),
                        requirePatientId(envelope, payload),
                        requireText(envelope, payload, "wardId"),
                        requireInstant(envelope, payload, "admittedAt"));
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_TRANSFERRED ->
                inpatientChargeService.onVisitTransferred(
                        requireVisitId(envelope, payload),
                        requirePatientId(envelope, payload),
                        requireText(envelope, payload, "fromWardId"),
                        requireText(envelope, payload, "toWardId"),
                        requireInstant(envelope, payload, "transferredAt"));
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGE_REQUESTED ->
                inpatientChargeService.onDischargeRequested(
                        requireVisitId(envelope, payload),
                        requirePatientId(envelope, payload),
                        requireInstant(envelope, payload, "requestedAt"));
            default -> log.info("住院就诊域事件未纳管（通配队列直返）：eventType={}", eventType);
        }
    }

    /**
     * 医嘱域业务体派发（包级直驱可测）：created 计价/executed 确认/stopped+三终态（cancelled/
     * revoked/audit-rejected）截断；audited 到店 info 留痕直返（冻结载荷不携 items，计价数据面
     * 在 created——类注释适配留痕）。
     *
     * @param envelope 已解析信封，非空
     * @throws IllegalStateException 载荷缺定位键或 items 非数组（不合规帧死信留痕）时触发
     */
    void handleOrderEvent(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String eventType = envelope.eventType();
        switch (eventType) {
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_CREATED -> {
                String m04OrderNo = requireText(envelope, payload, "m04OrderNo");
                JsonNode items = payload.path("items");
                // 明细数组为离散计价唯一数据面：非数组定性不合规帧（空数组合法——无计价行嘱托）
                if (!items.isArray()) {
                    throw new IllegalStateException(
                            "医嘱开立载荷不合规（items 非数组）：eventType=" + eventType + "，payload=" + payload);
                }
                inpatientChargeService.onOrderCreated(
                        m04OrderNo, requireVisitId(envelope, payload), requirePatientId(envelope, payload), items);
            }
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_EXECUTED ->
                inpatientChargeService.onOrderExecuted(
                        requireText(envelope, payload, "m04OrderNo"), requireVisitId(envelope, payload));
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED ->
                inpatientChargeService.onOrderStopped(
                        requireText(envelope, payload, "m04OrderNo"), requireVisitId(envelope, payload));
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_AUDIT_REJECTED,
                    BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_CANCELLED,
                    BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_REVOKED ->
                // 驳回/作废/撤回=医嘱不可能再执行的终态（V800 id 45/46、V901 id 67）：与 stopped
                // 同款截断语义收敛在途 PENDING 费用行，防未结清合计虚增误导出院费用预审
                inpatientChargeService.onOrderStopped(
                        requireText(envelope, payload, "m04OrderNo"), requireVisitId(envelope, payload));
            case BillingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_AUDITED ->
                log.info("医嘱审核通过事件到店（无业务动作——计价数据面在 created 载荷）：eventType={}", eventType);
            default -> log.info("住院医嘱域事件未纳管（通配队列直返）：eventType={}", eventType);
        }
    }

    /**
     * 载荷 visitId 守卫（费用定位键=CF-3 住院就诊号；缺失即不合规帧显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 住院就诊号，非空
     */
    private static String requireVisitId(EventEnvelope envelope, JsonNode payload) {
        return requireText(envelope, payload, "visitId");
    }

    /**
     * 载荷 patientId 守卫（计费唯一键首要素；缺失/非法即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 患者主索引，非空
     */
    private static long requirePatientId(EventEnvelope envelope, JsonNode payload) {
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            throw new IllegalStateException(
                    "住院事件载荷不合规（patientId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return patientId;
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
        String value = textOrNull(payload, field);
        if (value == null) {
            throw new IllegalStateException(
                    "住院事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
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
        String value = textOrNull(payload, field);
        if (value == null) {
            throw new IllegalStateException(
                    "住院事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "住院事件载荷不合规（" + field + " 非法时点文本）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null）。
     *
     * @param payload 载荷 JSON，非空
     * @param field   字段名，非空
     * @return 字段文本值，可空
     */
    private static String textOrNull(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}

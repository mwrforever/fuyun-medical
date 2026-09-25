package com.fuyun.inpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.service.OrderAuditService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * M06 药师审方回执消费侧（V800 id 53/54 冻结载荷，FU-M04-05 审核链回执驱动面）：通过回执
 * （target=m04_order_no/auditNo/auditOperator/auditedAt）驱动 CREATED→AUDITED + audited.drug
 * 事件；驳回回执（追加 rejectReason——必附药师意见）驱动 CREATED→AUDIT_REJECTED +
 * audit-rejected 事件。幂等两层：eventId 构件幂等（IdempotentConsumerSupport 三段式）+
 * 业务级已达态跳过（OrderAuditServiceImpl 重复回执零副作用）。载荷以 JsonNode 读（禁依赖
 * pharmacy api——模块依赖单向）。归 internal/；Bean 注册点 InpatientMessagingConfig @Import。
 */
@Slf4j
public class PharmacyAuditReplyListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final OrderAuditService orderAuditService;

    /**
     * 全参构造器（装配归 InpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * inpatientConsumerSupport——GC7 common 模板类多实例红线）。
     *
     * @param consumerSupport   消费模板，非空；定绑 InpatientMessagingConfig inpatientConsumerSupport Bean
     * @param orderAuditService 医嘱审核与控制服务（回执驱动迁移承载面），非空
     */
    public PharmacyAuditReplyListener(
            @Qualifier("inpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            OrderAuditService orderAuditService) {
        this.consumerSupport = consumerSupport;
        this.orderAuditService = orderAuditService;
    }

    /**
     * 审方通过回执入口（q.inpatient.pharmacy.medication-order.audit-completed）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + InpatientMessagingConstants.MODULE + "."
                    + InpatientMessagingConstants.EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_COMPLETED)
    public void onAuditCompleted(Message message) {
        consumerSupport.consume(message, this::handleAuditCompleted);
    }

    /**
     * 审方驳回回执入口（q.inpatient.pharmacy.medication-order.audit-rejected）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + InpatientMessagingConstants.MODULE + "."
                    + InpatientMessagingConstants.EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_REJECTED)
    public void onAuditRejected(Message message) {
        consumerSupport.consume(message, this::handleAuditRejected);
    }

    /**
     * 审方通过回执业务体（包级直驱可测）：载荷读 target（m04 医嘱号）/auditNo/auditOperator/
     * auditedAt（V800 id 53 冻结契约），驱动审方通过迁移。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 53 冻结
     * @throws IllegalStateException 缺 target（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleAuditCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String orderNo = requireTarget(envelope, payload);
        orderAuditService.onPharmacistApproved(
                orderNo,
                textOrNull(payload, "auditNo"),
                textOrNull(payload, "auditOperator"),
                replyTime(envelope, payload));
    }

    /**
     * 审方驳回回执业务体（包级直驱可测）：载荷读 target/auditNo/rejectReason/auditOperator/
     * auditedAt（V800 id 54 冻结契约——rejectReason 必附药师意见），驱动驳回迁移。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 54 冻结
     * @throws IllegalStateException 缺 target 或缺 rejectReason（驳回必附药师意见——不合规帧
     *                 禁静默吞，死信留痕）时触发
     */
    void handleAuditRejected(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String orderNo = requireTarget(envelope, payload);
        // 驳回必附药师意见（医生站重提修改依据——缺失定性不合规帧）
        String rejectReason = textOrNull(payload, "rejectReason");
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new IllegalStateException(
                    "审方驳回回执载荷不合规（缺 rejectReason 药师意见）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        orderAuditService.onPharmacistRejected(
                orderNo,
                textOrNull(payload, "auditNo"),
                rejectReason,
                textOrNull(payload, "auditOperator"),
                replyTime(envelope, payload));
    }

    /**
     * 载荷 target 守卫（回执定位键=m04 医嘱号；缺失即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return m04 医嘱号，非空
     */
    private static String requireTarget(EventEnvelope envelope, JsonNode payload) {
        String orderNo = textOrNull(payload, "target");
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalStateException(
                    "审方回执载荷不合规（缺 target 医嘱号）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return orderNo;
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null——auditNo/auditOperator 可选承载）。
     *
     * @param payload 载荷 JSON，非空
     * @param field   字段名，非空
     * @return 字段文本值，可空
     */
    private static String textOrNull(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 回执时点读取（auditedAt）：缺失或解析失败容错取信封 occurredAt（审核时点非状态面依据，
     * 禁因时点格式异常丢回执——warn 留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 回执审核时点，非空
     */
    private static Instant replyTime(EventEnvelope envelope, JsonNode payload) {
        String auditedAt = textOrNull(payload, "auditedAt");
        if (auditedAt != null) {
            try {
                return Instant.parse(auditedAt);
            } catch (DateTimeParseException e) {
                log.warn("审方回执 auditedAt 解析失败（容错取信封时点）：auditedAt={}，occurredAt={}", auditedAt, envelope.occurredAt());
            }
        }
        return envelope.occurredAt();
    }
}

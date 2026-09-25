package com.fuyun.pharmacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IMedicationReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 住院医嘱开立消费侧（inpatient.order.created drug 子键精确绑定，V901 id 66 登记契约）：
 * drug 子键驱动 M06 审方任务生成（落 order_medication 快照 + review_task 待审）——薄切片
 * 全人工审方；非 drug 子键（lab/exam 等）由绑定键天然隔离不入本队列。
 *
 * <p>幂等双层实测口径：eventId 构件幂等（IdempotentConsumerSupport 三段式，键=eventId+消费
 * 模块，与 eventType 无关——子键后缀不参与幂等键，登记名比对场景无前缀归并需求）；业务级
 * 幂等由 uk_medication_order_no/uk_review_medication 兜底（重提重发事件同任务复位重开）。
 * 载荷以 JsonNode 读（禁依赖 inpatient api——模块依赖单向红线）。归 internal/，Bean 注册点
 * PharmacyMessagingConfig @Import。
 */
@Slf4j
public class MedicationOrderReviewListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final IMedicationReviewService medicationReviewService;

    /**
     * 全参构造器（装配归 PharmacyMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * pharmacyConsumerSupport——common 模板类多实例红线）。
     *
     * @param consumerSupport        消费模板，非空；定绑 PharmacyMessagingConfig pharmacyConsumerSupport Bean
     * @param medicationReviewService 审方服务（落两表与决策面承载），非空
     */
    public MedicationOrderReviewListener(
            @Qualifier("pharmacyConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IMedicationReviewService medicationReviewService) {
        this.consumerSupport = consumerSupport;
        this.medicationReviewService = medicationReviewService;
    }

    /**
     * 医嘱开立 drug 子键消费入口（q.pharmacy.inpatient.order.created.drug，精确绑定键见
     * PharmacyMessagingConfig 自声明 Bean——governance 订阅登记按登记名精确匹配无法承载子键）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = PharmacyMessagingConstants.CONSUMER_QUEUE_PREFIX
                    + PharmacyMessagingConstants.MODULE
                    + "."
                    + PharmacyMessagingConstants.BINDING_KEY_INPATIENT_ORDER_CREATED_DRUG)
    public void onOrderCreated(Message message) {
        consumerSupport.consume(message, this::handleOrderCreated);
    }

    /**
     * 医嘱开立业务体（包级直驱可测）：载荷读 m04OrderNo/visitId/patientId/freqCode/items
     * （V901 id 66 冻结契约子集——orderType 由绑定键保证 drug，无需业务侧复判），快照 JSON
     * 整段透传服务侧落库。
     *
     * @param envelope 事件信封，非空；载荷契约 V901 id 66 冻结
     * @throws IllegalStateException 缺 m04OrderNo/visitId/patientId 或 items 非数组（不合规帧
     *                 禁静默吞——死信留痕）时触发
     */
    void handleOrderCreated(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String m04OrderNo = requireText(envelope, payload, "m04OrderNo");
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            throw new IllegalStateException(
                    "医嘱开立载荷不合规（patientId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        JsonNode items = payload.path("items");
        // 明细快照为审方工作台药品明细唯一数据源：缺失或非数组定性不合规帧（空数组合法——纯嘱托类 drug 行）
        if (!items.isArray()) {
            throw new IllegalStateException(
                    "医嘱开立载荷不合规（items 非数组）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        medicationReviewService.onOrderCreated(
                m04OrderNo, visitId, patientId, textOrNull(payload, "freqCode"), items.toString());
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
                    "医嘱开立载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null——freqCode 临时医嘱可空承载）。
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

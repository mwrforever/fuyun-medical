package com.fuyun.nursing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.service.IOrderExecutionOperateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 摆药签收衔接消费侧（M05，P2 PR-3 Task 5）：消费 pharmacy.dispense.completed
 * （V702 id 28 + V1111 住院扩列）——住院行（m04OrderNo 非空）驱动药品执行单批量自动
 * SIGNED 与 PIVAS 升格建链（消费体归 {@link IOrderExecutionOperateService#onDispenseCompleted}）。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear（审计列落 SYSTEM 文本；inpatient 侧白名单同源）。
 *
 * <p>bag_label_code 取数（先实测裁决）：载荷 lines[].traceCodes 首个非空溯源码；全空回退
 * 首行 itemCode#batchNo 摘要（列宽 64 内）；PIVAS 载荷连行集都缺属不合规帧——ISE 进死信
 * 留痕。门诊行（m04OrderNo=null）直接确认忽略（V1111 双语义承载）。载荷以 JsonNode 读
 * （禁依赖 pharmacy api record——消费侧不依赖生产者 jar，MedicationOrderReviewListener
 * 同款口径）。载荷字段缺失抛 ISE 进死信留痕（不合规帧禁静默吞）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class DispenseSignoffListener {

    /** 系统触发面操作者（GC15 桥接口径——与 inpatient 侧 SYSTEM 白名单字面量同源） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    /** 瓶签摘要回退分隔符（itemCode#batchNo 拼接——lines 摘要承载） */
    private static final String LABEL_FALLBACK_SEPARATOR = "#";

    private final IdempotentConsumerSupport consumerSupport;

    private final IOrderExecutionOperateService operateService;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，必须 @Qualifier 定绑 nursingConsumerSupport——GC7 common 模板类多实例红线；
     * 单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param operateService  执行单操作域服务（摆药签收衔接消费体），非空
     */
    public DispenseSignoffListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IOrderExecutionOperateService operateService) {
        this.consumerSupport = consumerSupport;
        this.operateService = operateService;
    }

    /**
     * 摆药完成消费入口（q.nursing.pharmacy.dispense.completed，V702 id 28）：
     * 住院行驱动批量签收与 PIVAS 升格，门诊行忽略确认。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_PHARMACY_DISPENSE_COMPLETED)
    public void onDispenseCompleted(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleDispenseCompleted);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 签收衔接业务体（包级直驱可测）：载荷读 m04OrderNo/dispenseType/dispensePlanNo
     * （V1111 住院扩列子集）→ 袋签码推导（traceCodes 首位/行摘要回退）→ 委托操作域批量
     * 签收与升格建链。门诊行（m04OrderNo=null）info 留痕直接确认（V1111 只增可空字段，
     * 既有消费方按原子集取用不受影响）。
     *
     * @param envelope 事件信封，非空；载荷契约 V702 id 28 + V1111 扩列冻结
     * @throws IllegalStateException 住院行缺冻结字段或袋签码不可推导（不合规帧禁静默吞——
     *                 死信留痕）时触发
     */
    void handleDispenseCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String m04OrderNo = payload.path("m04OrderNo").asText(null);
        if (m04OrderNo == null || m04OrderNo.isBlank()) {
            // 门诊行无住院衔接锚：直接确认忽略（占位事件族既有语义）
            log.info(
                    "摆药完成事件忽略（门诊行无住院衔接锚）：eventType={}，dispenseNo={}",
                    envelope.eventType(),
                    payload.path("dispenseNo").asText());
            return;
        }
        String dispenseType = requireText(envelope, payload, "dispenseType");
        String dispensePlanNo = requireText(envelope, payload, "dispensePlanNo");
        String bagLabelCode = deriveBagLabelCode(envelope, payload);
        log.info(
                "摆药完成事件消费（住院行签收衔接）：eventType={}，m04OrderNo={}，dispenseType={}，dispensePlanNo={}，bagLabelCode={}",
                envelope.eventType(),
                m04OrderNo,
                dispenseType,
                dispensePlanNo,
                bagLabelCode);
        operateService.onDispenseCompleted(m04OrderNo, dispenseType, dispensePlanNo, bagLabelCode);
    }

    /**
     * 袋签码推导（brief「先实测」点落定）：lines[].traceCodes 首个非空溯源码优先；
     * 全空回退首行 itemCode#batchNo 摘要（列宽 64 内承载）；PIVAS 住院行连行集都缺属
     * 不合规帧——ISE 死信留痕。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 袋签码（≤64），非空
     */
    private static String deriveBagLabelCode(EventEnvelope envelope, JsonNode payload) {
        JsonNode lines = payload.path("lines");
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                JsonNode traceCodes = line.path("traceCodes");
                if (traceCodes.isArray()) {
                    for (JsonNode code : traceCodes) {
                        String value = code.asText(null);
                        if (value != null && !value.isBlank()) {
                            return value.length() > 64 ? value.substring(0, 64) : value;
                        }
                    }
                }
            }
            if (!lines.isEmpty()) {
                // 行摘要回退：首行 itemCode#batchNo（溯源码缺位的 PIVA 成品仍可建链定位）
                String summary = lines.get(0).path("itemCode").asText("")
                        + LABEL_FALLBACK_SEPARATOR
                        + lines.get(0).path("batchNo").asText("");
                if (!summary.isBlank() && !LABEL_FALLBACK_SEPARATOR.equals(summary)) {
                    return summary.length() > 64 ? summary.substring(0, 64) : summary;
                }
            }
        }
        // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
        throw new IllegalStateException(
                "摆药完成载荷不合规（住院行无可推导袋签码）：eventType=" + envelope.eventType() + "，payload=" + payload);
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
            throw new IllegalStateException(
                    "摆药完成载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }
}

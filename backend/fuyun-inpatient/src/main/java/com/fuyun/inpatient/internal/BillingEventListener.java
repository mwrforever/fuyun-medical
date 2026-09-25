package com.fuyun.inpatient.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.service.AdmissionService;
import com.fuyun.inpatient.service.DischargeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * M13 计费联动消费侧（V605 id 19/21 与 V1002 id 73 冻结载荷，FU-M04-07/08 承载面）：
 * 结算完成回执（visitId/settleType=IN 出院结算分支）驱动 DischargeService.onSettlementCompleted
 * 落 settlement_completed_at 标记（离院确认双条件之一）；挂账审批放行回执（visitId/approvalNo）
 * 驱动 onArrearsApproved 将 BLOCKED 申请转 READY（放行唯一驱动）；押金变动回执
 * （accountId/patientId/visitId/balance/status）驱动 AdmissionService.onDepositChanged 欠费标识
 * 本地裁决与 CAS 刷新（Task 10 落地——余额与押金下限阈值的比较归服务层，阈值全局一份）。
 * 幂等两层：eventId 构件幂等（IdempotentConsumerSupport 三段式）+ 业务级 CAS 限定幂等
 * （IS NULL/BLOCKED/目标值异值条件更新零行直返）。载荷以 JsonNode 读（消费侧禁依赖 producer
 * 载荷 record——PharmacyAuditReplyListener 同款口径）。归 internal/；Bean 注册点
 * InpatientMessagingConfig @Import。
 */
@Slf4j
public class BillingEventListener {

    /** 结算类型：住院结算（出院结算分支判定值——V605 id 19 settleType 词表 OUT/IN） */
    private static final String SETTLE_TYPE_IN = "IN";

    private final IdempotentConsumerSupport consumerSupport;

    private final DischargeService dischargeService;

    private final AdmissionService admissionService;

    /**
     * 全参构造器（装配归 InpatientMessagingConfig @Import；消费模板多候选 @Qualifier 定绑
     * inpatientConsumerSupport——GC7 common 模板类多实例红线）。
     *
     * @param consumerSupport 消费模板，非空；定绑 InpatientMessagingConfig inpatientConsumerSupport Bean
     * @param dischargeService 出院管理服务（结算标记/挂账放行业务体），非空
     * @param admissionService 入院登记域服务（押金变动欠费标识刷新业务体——visit 行本地属性），非空
     */
    public BillingEventListener(
            @Qualifier("inpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            DischargeService dischargeService,
            AdmissionService admissionService) {
        this.consumerSupport = consumerSupport;
        this.dischargeService = dischargeService;
        this.admissionService = admissionService;
    }

    /**
     * 结算完成回执入口（q.inpatient.billing.settlement.completed）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + InpatientMessagingConstants.MODULE + "."
                    + InpatientMessagingConstants.EVENT_SUB_BILLING_SETTLEMENT_COMPLETED)
    public void onSettlementCompleted(Message message) {
        consumerSupport.consume(message, this::handleSettlementCompleted);
    }

    /**
     * 挂账审批放行回执入口（q.inpatient.billing.arrears.approved）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + InpatientMessagingConstants.MODULE + "."
                    + InpatientMessagingConstants.EVENT_SUB_BILLING_ARREARS_APPROVED)
    public void onArrearsApproved(Message message) {
        consumerSupport.consume(message, this::handleArrearsApproved);
    }

    /**
     * 押金变动回执入口（q.inpatient.billing.deposit.changed）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = "q." + InpatientMessagingConstants.MODULE + "."
                    + InpatientMessagingConstants.EVENT_SUB_BILLING_DEPOSIT_CHANGED)
    public void onDepositChanged(Message message) {
        consumerSupport.consume(message, this::handleDepositChanged);
    }

    /**
     * 结算完成回执业务体（包级直驱可测）：载荷读 visitId/settleType（V605 id 19 冻结契约），
     * 仅住院结算分支（settleType=IN）驱动出院结算标记——门诊结算（OUT）与本域无涉直返。
     *
     * @param envelope 事件信封，非空；载荷契约 V605 id 19 冻结
     * @throws IllegalStateException 缺 visitId（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleSettlementCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireVisitId(envelope, payload);
        String settleType = textOrNull(payload, "settleType");
        if (!SETTLE_TYPE_IN.equals(settleType)) {
            // 门诊结算回执（settleType=OUT）——本消费面仅承付出院结算分支，info 留痕直返
            log.info("结算完成回执非住院结算分支（跳过出院标记）：visitId={}，settleType={}", visitId, settleType);
            return;
        }
        dischargeService.onSettlementCompleted(visitId, envelope.occurredAt());
    }

    /**
     * 挂账审批放行回执业务体（包级直驱可测）：载荷读 visitId/approvalNo（V1002 id 73 载荷
     * 契约——登记文件归 Task 13 落地，字段以本消费面冻结口径承载），驱动 BLOCKED→READY。
     *
     * @param envelope 事件信封，非空；载荷契约 V1002 id 73 冻结
     * @throws IllegalStateException 缺 visitId 或缺 approvalNo（放行凭证必附——不合规帧禁
     *                 静默吞）时触发
     */
    void handleArrearsApproved(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireVisitId(envelope, payload);
        String approvalNo = textOrNull(payload, "approvalNo");
        if (approvalNo == null) {
            throw new IllegalStateException(
                    "挂账审批放行回执载荷不合规（缺 approvalNo 审批单号）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        dischargeService.onArrearsApproved(visitId, approvalNo);
    }

    /**
     * 押金变动回执业务体（包级直驱可测）：载荷读 visitId/balance（V605 id 21 冻结契约——
     * accountId/patientId/visitId/balance/status 五组件，本面按需取 visitId/balance），透传
     * 欠费标识刷新服务（余额与押金下限阈值的比较、CAS 与幂等归 AdmissionService——Task 10
     * 落地）；status（NORMAL/ARREARS）为 billing 侧自身预警判定，M04 以本地阈值独立裁决
     * 不采信该字段。
     *
     * @param envelope 事件信封，非空；载荷契约 V605 id 21 冻结
     * @throws IllegalStateException 缺 visitId 或缺 balance（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleDepositChanged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireVisitId(envelope, payload);
        // balance 为变动后余额（分）——GC18 零金额输入红线：金额仅为事件载荷消费面
        JsonNode balance = payload.path("balance");
        if (!balance.isNumber()) {
            throw new IllegalStateException(
                    "押金变动回执载荷不合规（缺 balance 变动后余额）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        admissionService.onDepositChanged(visitId, balance.asLong());
    }

    /**
     * 载荷 visitId 守卫（回执定位键=CF-3 住院就诊号；缺失即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 住院就诊号（I 型 14 位），非空
     */
    private static String requireVisitId(EventEnvelope envelope, JsonNode payload) {
        String visitId = textOrNull(payload, "visitId");
        if (visitId == null) {
            throw new IllegalStateException(
                    "billing 回执载荷不合规（缺 visitId 就诊号）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return visitId;
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null——settleType 等可选承载面）。
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

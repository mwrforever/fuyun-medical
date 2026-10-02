package com.fuyun.inpatient.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.enums.PlanStatus;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 护理执行回执对账消费侧（M04，P2 PR-3 Task 5）：消费 nursing.order-execution.completed
 * （V800 id 64，M05 执行单终态辅路径回执）——按 m04PlanNo 比对计划状态，EXECUTED=对账一致
 * （debug 日志）；非 EXECUTED=对账差异（warn 日志含 executionNo/planNo）。
 *
 * <p>差异处置形态（计划注记裁决）：无 order_audit 落行、无阻断、无反向修复——Spec「异常
 * 清单」降级为日志留痕，差异清单经日志聚合（主路径回签端口与补偿扫描已承载最终一致，
 * 本消费面仅对账观测）。载荷以 JsonNode 读（禁依赖 nursing api——inpatient 禁反向依赖
 * nursing，GC6 模块依赖单向红线；InpatientOrderEventListener 同款口径）。载荷字段缺失
 * 抛 ISE 进死信留痕（不合规帧禁静默吞）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 InpatientMessagingConfig @Import。
 */
@Slf4j
public class NursingExecutionReconcileListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final OrderExecutePlanMapper planMapper;

    /**
     * 全参构造器（装配归 InpatientMessagingConfig @Import；消费模板系 common 基类跨模块
     * 多实例 Bean，必须 @Qualifier 定绑 inpatientConsumerSupport——GC7 common 模板类多实例
     * 红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 InpatientMessagingConfig inpatientConsumerSupport Bean
     * @param planMapper      执行计划 mapper，非空；对账比对取数面（planNo 定位）
     */
    public NursingExecutionReconcileListener(
            @Qualifier("inpatientConsumerSupport") IdempotentConsumerSupport consumerSupport,
            OrderExecutePlanMapper planMapper) {
        this.consumerSupport = consumerSupport;
        this.planMapper = planMapper;
    }

    /**
     * 执行回执对账消费入口（q.inpatient.nursing.order-execution.completed，V800 id 64）：
     * 比对计划 EXECUTED 态，差异仅 warn 留痕。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = InpatientMessagingConstants.QUEUE_PREFIX
                    + InpatientMessagingConstants.EVENT_SUB_NURSING_ORDER_EXECUTION_COMPLETED)
    public void onOrderExecutionCompleted(Message message) {
        consumerSupport.consume(message, this::handleReconcile);
    }

    /**
     * 对账业务体（包级直驱可测）：载荷读 executionNo/m04PlanNo（V800 id 64 冻结契约子集）
     * → 按 planNo 定位计划行比对状态。计划缺行按差异口径留痕（warn——回签主路径未达或
     * 计划未生成，补偿面承载收敛，不构成不合规帧）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 64 冻结
     * @throws IllegalStateException 缺任一冻结定位字段（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleReconcile(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String executionNo = requireText(envelope, payload, "executionNo");
        String planNo = requireText(envelope, payload, "m04PlanNo");
        // 数据库读操作：计划行定位（uk planNo；逻辑删由 @TableLogic 自动过滤）
        OrderExecutePlan plan =
                planMapper.selectOne(Wrappers.<OrderExecutePlan>lambdaQuery().eq(OrderExecutePlan::getPlanNo, planNo));
        if (plan == null) {
            // 对账差异：回执计划锚缺行（主路径未达/乱序）——日志留痕归补偿面收敛
            log.warn(
                    "护理执行回执对账差异（计划行不存在）：eventType={}，executionNo={}，planNo={}",
                    envelope.eventType(),
                    executionNo,
                    planNo);
            return;
        }
        if (PlanStatus.EXECUTED.getCode().equals(plan.getStatus())) {
            // 对账一致：主路径回签已生效，辅路径回执闭环确认
            log.debug(
                    "护理执行回执对账一致：eventType={}，executionNo={}，planNo={}，planStatus={}",
                    envelope.eventType(),
                    executionNo,
                    planNo,
                    plan.getStatus());
            return;
        }
        // 对账差异：回执已发而计划未 EXECUTED（主路径未达→COMPENSATING 补偿承载 / PENDING
        // 窗口）——warn 留痕（无 order_audit 落行，Spec「异常清单」降级为日志留痕裁决）
        log.warn(
                "护理执行回执对账差异（计划非 EXECUTED 态）：eventType={}，executionNo={}，planNo={}，planStatus={}",
                envelope.eventType(),
                executionNo,
                planNo,
                plan.getStatus());
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
                    "护理执行回执载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }
}

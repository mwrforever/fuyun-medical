package com.fuyun.ward.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.mapper.WardCallMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 拔针复位消费监听器（P2 PR-2 Task 12 Step 5 消费骨架）：q.ward.nursing.infusion.completed
 * （V800 id 63 登记，payload 契约 executionNo/patientId/visitId/endedAt）的标准范式消费执行点，
 * 业务体为拔针复位——按 patient_id 复位该患者全部活跃输液呼叫行（系统动作 CAS，updated_by=system）。
 *
 * <p><b>联调债申报</b>：事件登记在位而 nursing 发布端 P2 PR-3 实装——本监听器与队列声明落位后
 * 订阅空队列等待，PR-3 发布后生效；载荷字段以 V800 payload_desc 冻结文本为契约锚，PR-3 发布
 * 实装时若字段漂移以登记侧修订为准（patientId 缺位帧仅留痕不复位）。
 *
 * <p>复位键说明：V800 id 63 载荷无 deviceId 锚，patient_id 为唯一可用复位键（V1100
 * idx_ward_call_patient_active 准入）；executionNo 进消费日志留痕（ward 呼叫行无 executionNo
 * 承载列，台账对账经日志）。
 *
 * <p><b>确认机制</b>：RabbitMQ 容器 AUTO 确认（宪法 A.5-5）；幂等分域 consumer_module=ward
 * （wardConsumerSupport @Qualifier 定绑——GC7 红线）；归 internal/ 包（宪法 B.1）。
 */
@Slf4j
public class NursingInfusionCompletedListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final WardCallMapper callMapper;

    /**
     * 全参构造器（装配归 WardConfig @Import，backend 宪法 B.1）。
     *
     * @param consumerSupport 消费模板，非空；定绑 WardMessagingConfig wardConsumerSupport Bean
     * @param callMapper      呼叫行 mapper，非空；拔针复位 CAS 通道
     */
    public NursingInfusionCompletedListener(
            @Qualifier("wardConsumerSupport") IdempotentConsumerSupport consumerSupport, WardCallMapper callMapper) {
        this.consumerSupport = consumerSupport;
        this.callMapper = callMapper;
    }

    /**
     * 输注结束事件消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.ward.nursing.infusion.completed
     */
    @RabbitListener(queues = WardMessagingConstants.QUEUE_NURSING_INFUSION_COMPLETED)
    public void onInfusionCompleted(Message message) {
        consumerSupport.consume(message, this::handleInfusionCompleted);
    }

    /**
     * 消费业务体（包级直驱可测）：载荷 JsonNode 解析（PR-3 发布实装前无 record 契约类——ward
     * 不依赖 nursing 模块，B.2-2）+ 患者锚拔针复位。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷非 JSON 对象（契约不符）——按消费失败处置（三段式③失败
     *                               收尾后重抛走死信），禁止静默吞错
     */
    void handleInfusionCompleted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        if (!payload.isObject()) {
            // EX-19 收口 C 类：内部事件契约断言（载荷须为 JSON 对象），保留 ISE——上抛由三段式③
            // 失败收尾（FAILED 留痕后重抛走死信），消费失败→重试→死信链路语义不变，零行为变化
            throw new IllegalStateException("输注结束事件载荷与契约不符：event_id=" + envelope.eventId());
        }
        String executionNo = payload.path("executionNo").asText(null);
        JsonNode patientNode = payload.path("patientId");
        if (patientNode.isNull() || patientNode.isMissingNode()) {
            // 患者锚缺位：仅留痕不复位（复位语义以 V800 载荷 patientId 为锚）
            log.info("输注结束帧缺患者锚不复位（留痕）：executionNo={}，event_id={}", executionNo, envelope.eventId());
            return;
        }
        long patientId = patientNode.asLong();
        // 数据库写操作：拔针复位 CAS（患者维度活跃输液呼叫批量置 CANCELLED，系统动作）
        int reset = callMapper.cancelActiveInfusionByPatient(patientId);
        log.info(
                "拔针复位完成：executionNo={}，patientId={}，resetCalls={}，event_id={}，traceId={}",
                executionNo,
                patientId,
                reset,
                envelope.eventId(),
                envelope.traceId());
    }
}

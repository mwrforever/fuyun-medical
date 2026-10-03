package com.fuyun.nursing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.service.IInfusionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * IoT 告警执行单挂接消费侧（M05 输液闭环，P2 PR-3 Task 6）：iot.alarm.triggered/escalated/
 * closed 三路（V1004 id 74–76 登记，队列随 NursingMessagingConfig SUBSCRIBED_EVENT_TYPES 声明）
 * ——triggered 按载荷 patientId 直配在途输液执行单做升级挂单（不新建任务）；escalated 按
 * latest_alarm_no 反查同款累计（载荷无 patientId）；closed 挂接锚复位（escalation_count 保留
 * 追溯）。消费体归 {@link IInfusionService} 三方法。
 *
 * <p>GC15 operator 桥：MQ 消费链路无登录上下文——入口显式 OperatorContextHolder.set
 * ("SYSTEM") + try/finally clear（审计列落 SYSTEM 文本）。载荷以 JsonNode 读（消费侧不依赖
 * 生产者 jar，经 EventEnvelope JSON 取冻结子集——MedicationOrderReviewListener/ward 拔针复位
 * 先例）；告警号缺失属不合规帧 ISE 死信留痕；triggered 载荷 patientId 缺位（公共区域设备
 * 告警）info 跳过（无患者维度挂接面，非异常路径）。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class IotAlarmExecutionListener {

    /** 系统触发面操作者（GC15 桥接口径——审计列落位与 finally 清理配对） */
    private static final String SYSTEM_OPERATOR_TEXT = "SYSTEM";

    private final IdempotentConsumerSupport consumerSupport;

    private final IInfusionService infusionService;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板系 common 基类跨模块多实例
     * Bean，必须 @Qualifier 定绑 nursingConsumerSupport——GC7 common 模板类多实例红线；
     * 单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param infusionService 输液闭环域服务（三路消费体），非空
     */
    public IotAlarmExecutionListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            IInfusionService infusionService) {
        this.consumerSupport = consumerSupport;
        this.infusionService = infusionService;
    }

    /**
     * 告警触发消费入口（q.nursing.iot.alarm.triggered，V1004 id 74）：患者维度升级挂单。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_IOT_ALARM_TRIGGERED)
    public void onAlarmTriggered(Message message) {
        // GC15 operator 桥：系统链路操作者显式落位（finally 清理防线程复用残留）
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleAlarmTriggered);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 告警升级动作消费入口（q.nursing.iot.alarm.escalated，V1004 id 75）：挂接锚反查同款累计。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_IOT_ALARM_ESCALATED)
    public void onAlarmEscalated(Message message) {
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleAlarmEscalated);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 告警关闭消费入口（q.nursing.iot.alarm.closed，V1004 id 76）：挂接锚复位。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_IOT_ALARM_CLOSED)
    public void onAlarmClosed(Message message) {
        OperatorContextHolder.set(SYSTEM_OPERATOR_TEXT);
        try {
            consumerSupport.consume(message, this::handleAlarmClosed);
        } finally {
            OperatorContextHolder.clear();
        }
    }

    /**
     * 触发路消费业务体（包级直驱可测）：载荷读 alarmNo/patientId/wardId/alarmLevel/metricCode
     * 冻结子集 → patientId 直配升级挂单（无患者锚的公共区域告警 info 跳过）。
     *
     * @param envelope 事件信封，非空；载荷契约 V1004 id 74 冻结（AlarmTriggeredPayload 子集）
     * @throws IllegalStateException 告警号缺失（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleAlarmTriggered(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String alarmNo = requireAlarmNo(envelope, payload);
        JsonNode patientNode = payload.path("patientId");
        if (patientNode.isNull() || patientNode.isMissingNode()) {
            // 无患者锚（设备未绑定在院患者的公共区域告警）：无挂接面，info 留痕跳过
            log.info(
                    "告警触发无患者锚跳过挂单：eventType={}，alarmNo={}，deviceId={}",
                    envelope.eventType(),
                    alarmNo,
                    payload.path("deviceId").asText());
            return;
        }
        int escalated = infusionService.escalateOnAlarmTriggered(patientNode.asLong(), alarmNo);
        log.info(
                "告警触发升级挂单受理：eventType={}，alarmNo={}，patientId={}，wardId={}，alarmLevel={}，metricCode={}，升级行数={}，event_id={}",
                envelope.eventType(),
                alarmNo,
                patientNode.asLong(),
                payload.path("wardId").asLong(),
                payload.path("alarmLevel").asText(),
                payload.path("metricCode").asText(),
                escalated,
                envelope.eventId());
    }

    /**
     * 升级路消费业务体（包级直驱可测）：载荷读 alarmNo 冻结子集 → 挂接锚反查同款累计
     * （escalated 载荷无 patientId——latest_alarm_no 反查，见 IInfusionService 注释）。
     *
     * @param envelope 事件信封，非空；载荷契约 V1004 id 75 冻结（AlarmEscalatedPayload 子集）
     * @throws IllegalStateException 告警号缺失（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleAlarmEscalated(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String alarmNo = requireAlarmNo(envelope, payload);
        int escalated = infusionService.escalateOnAlarmEscalated(alarmNo);
        log.info(
                "告警升级动作累计受理：eventType={}，alarmNo={}，escalationLevel={}，升级行数={}，event_id={}",
                envelope.eventType(),
                alarmNo,
                payload.path("escalationLevel").asInt(),
                escalated,
                envelope.eventId());
    }

    /**
     * 关闭路消费业务体（包级直驱可测）：载荷读 alarmNo 冻结子集 → 挂接锚复位
     * （escalation_count 保留追溯）。
     *
     * @param envelope 事件信封，非空；载荷契约 V1004 id 76 冻结（AlarmClosedPayload 子集）
     * @throws IllegalStateException 告警号缺失（不合规帧禁静默吞——死信留痕）时触发
     */
    void handleAlarmClosed(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String alarmNo = requireAlarmNo(envelope, payload);
        int reset = infusionService.resetAlarmClosed(alarmNo);
        log.info(
                "告警关闭挂接复位受理：eventType={}，alarmNo={}，closedBy={}，复位行数={}，event_id={}",
                envelope.eventType(),
                alarmNo,
                payload.path("closedBy").asText(),
                reset,
                envelope.eventId());
    }

    /**
     * 告警号必填守卫（三路共用定位键；缺失/空白即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 告警业务号，非空
     */
    private static String requireAlarmNo(EventEnvelope envelope, JsonNode payload) {
        String alarmNo = payload.path("alarmNo").asText(null);
        if (alarmNo == null || alarmNo.isBlank()) {
            throw new IllegalStateException(
                    "告警事件载荷不合规（缺 alarmNo）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return alarmNo;
    }
}

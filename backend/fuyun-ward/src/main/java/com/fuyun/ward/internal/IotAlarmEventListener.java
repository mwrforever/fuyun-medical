package com.fuyun.ward.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.ward.cache.WardSeqGate;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 输液告急消费监听器（P2 PR-2 Task 12 Step 5；与 iot 侧 Task 9 同名类以 ward 包路径区分——
 * brief 明示）：q.ward.iot.alarm.triggered 的标准范式消费执行点，业务体为输液告急判定——
 * metricCode=INFUSION_SHORTAGE（词表缺位申报见常量）且 trigger_value≤5（红档）时落
 * ward_call 系统级呼叫行（call_type=INFUSION、source_ref=告警号、source=IOT）。
 *
 * <p>告警载荷无床位锚（AlarmTriggeredPayload 契约五元组无 bedId），落行 bed_id 为空——V1100
 * 列面可空申报的成立依据；病区/患者/设备三元组取载荷快照。
 *
 * <p><b>确认机制（红线 4，锁定决策 6）</b>：本监听器走 RabbitMQ 容器 <b>AUTO 确认</b>（宪法
 * A.5-5：监听方法成功返回即由容器确认，失败有界重试耗尽进 fy.dlx）；幂等分域走
 * MessageIdempotencyService 标准范式（Redis NX 前置 + received_event 唯一索引兜底），
 * consumer_module=ward（与 iot 侧联动链/扇出链分域——每消费者一队列先例）。
 *
 * <p><b>at-least-once 语义注记</b>：处理失败（落行失败/登记失败）不登记 PROCESSED，帧回归
 * broker 重投域——重投由幂等前置拦截；同告警号不同 eventId 的重发窗口由
 * countActiveInfusionBySourceRef 应用层前置 + uk_ward_call_infusion_active 部分唯一索引双层防重。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 fuyun-app WardConfig @Import（经 WardMessagingConfig @Import 链）。
 */
@Slf4j
public class IotAlarmEventListener {

    /** 输液告急红档阈值（ml）：trigger_value≤5 落呼叫行（brief 冻结 5ml 档；档位展示映射归看板） */
    private static final BigDecimal RED_THRESHOLD =
            BigDecimal.valueOf(WardMessagingConstants.INFUSION_RED_THRESHOLD_ML);

    private final IdempotentConsumerSupport consumerSupport;

    private final ObjectMapper objectMapper;

    private final WardCallMapper callMapper;

    private final WardSeqGate seqGate;

    /**
     * 全参构造器（装配归 WardConfig @Import，backend 宪法 B.1；消费模板系 common 基类跨模块
     * 多实例 Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 wardConsumerSupport——GC7
     * common 模板类多实例定绑红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 WardMessagingConfig wardConsumerSupport Bean
     * @param objectMapper    JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     * @param callMapper      呼叫行 mapper，非空；系统级呼叫落行通道
     * @param seqGate         业务号发号器，非空；落行签发 call_no
     */
    public IotAlarmEventListener(
            @Qualifier("wardConsumerSupport") IdempotentConsumerSupport consumerSupport,
            ObjectMapper objectMapper,
            WardCallMapper callMapper,
            WardSeqGate seqGate) {
        this.consumerSupport = consumerSupport;
        this.objectMapper = objectMapper;
        this.callMapper = callMapper;
        this.seqGate = seqGate;
    }

    /**
     * 告警触发事件消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.ward.iot.alarm.triggered 的信封线格式
     */
    @RabbitListener(queues = WardMessagingConstants.QUEUE_IOT_ALARM_TRIGGERED)
    public void onAlarmTriggered(Message message) {
        consumerSupport.consume(message, this::handleAlarmTriggered);
    }

    /**
     * 消费业务体（包级直驱可测）：载荷契约解析 + 输液告急判定 + 系统级呼叫落行。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 AlarmTriggeredPayload 契约不符（字段缺失或类型错误）
     *                               ——按消费失败处置（三段式③失败收尾后重抛走死信），禁止静默吞错
     */
    void handleAlarmTriggered(EventEnvelope envelope) {
        AlarmTriggeredPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), AlarmTriggeredPayload.class);
        } catch (JsonProcessingException e) {
            // 载荷不合规（缺字段/类型错）等同业务失败：上抛由三段式③失败收尾（FAILED 留痕后重抛）
            throw new IllegalStateException("告警触发事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "告警触发事件受理（输液告急判定）：alarmNo={}，metricCode={}，triggerValue={}，wardId={}，event_id={}，traceId={}",
                payload.alarmNo(),
                payload.metricCode(),
                payload.triggerValue(),
                payload.wardId(),
                envelope.eventId(),
                envelope.traceId());
        if (!WardMessagingConstants.INFUSION_SHORTAGE_METRIC_CODE.equals(payload.metricCode())) {
            // 非输液告急帧：本消费链只承接输液告急（其他告警消费归 iot 联动链/大屏扇出链）
            log.debug("非输液告急帧跳过落行：alarmNo={}，metricCode={}", payload.alarmNo(), payload.metricCode());
            return;
        }
        BigDecimal triggerValue = parseTriggerValue(payload.triggerValue());
        if (triggerValue == null || triggerValue.compareTo(RED_THRESHOLD) > 0) {
            // 档位外（>5ml 或非数值）：红档不足不落行（黄/橙档展示面归看板，无呼叫语义）
            log.info("输液告急帧未达红档不落行：alarmNo={}，triggerValue={}", payload.alarmNo(), payload.triggerValue());
            return;
        }
        if (callMapper.countActiveInfusionBySourceRef(payload.alarmNo()) > 0) {
            // 同告警活跃行防重（不同 eventId 同告警号重发窗口——应用层前置，部分唯一索引兜底）
            log.info("同告警活跃呼叫行已存在跳过落行：alarmNo={}", payload.alarmNo());
            return;
        }
        String callNo = seqGate.nextCallNo();
        WardCallEntity entity = new WardCallEntity();
        entity.setCallNo(callNo);
        entity.setWardId(payload.wardId());
        entity.setPatientId(payload.patientId());
        entity.setDeviceId(payload.deviceId());
        entity.setCallType(CallType.INFUSION);
        entity.setSource(CallSource.IOT);
        entity.setStatus(CallStatus.CREATED);
        entity.setEscalationCount(0);
        entity.setSourceRef(payload.alarmNo());
        // 数据库写操作：系统级呼叫落行（告警事件驱动的自动呼叫——非人工入口，与合并取消语义无冲突）
        callMapper.insert(entity);
        log.info(
                "输液告急系统级呼叫已落行：callNo={}，alarmNo={}，wardId={}，deviceId={}",
                callNo,
                payload.alarmNo(),
                payload.wardId(),
                payload.deviceId());
    }

    /**
     * 触发值文本解析（载荷契约保留原始形态文本，数值语义由消费方按 metricCode 解释）。
     *
     * @param triggerValue 触发值原文，可空
     * @return 数值；非数值文本/null 返回 null（调用方按档位外跳过）
     */
    private static BigDecimal parseTriggerValue(String triggerValue) {
        if (triggerValue == null || triggerValue.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(triggerValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

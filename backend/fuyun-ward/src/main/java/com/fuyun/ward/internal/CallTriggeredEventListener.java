package com.fuyun.ward.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.api.payload.CallTriggeredPayload;
import com.fuyun.ward.cache.WardSeqGate;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.entity.WardCallEntity;
import com.fuyun.ward.enums.CallSource;
import com.fuyun.ward.enums.CallStatus;
import com.fuyun.ward.enums.CallType;
import com.fuyun.ward.mapper.WardCallMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 设备呼叫触发消费监听器（P2 PR-2 Task 12 审查 Important-1 回接，M16-01 呼叫信令面的设备源
 * 落行链）：q.ward.iot.call.triggered（V1004 id 81 登记，载荷契约 CallTriggeredPayload——iot
 * api 冻结 record）的标准范式消费执行点，业务体为设备源呼叫落行——call_type=载荷 callType、
 * source=IOT、status=CREATED。
 *
 * <p><b>source_ref 语义申报</b>：落行 source_ref=载荷 callNo 组件。iot 侧发布语义为「触发引用/
 * 预留」（联动 CALL_TRANSFER 回接路径填联动执行号 linkageNo——触发链唯一引用），ward 侧将其
 * 留作对账锚（消费日志 + source_ref 落行，可回查 iot.iot_linkage_log）；ward 呼叫业务号由本域
 * WardSeqGate 签发（CALL{yyyyMMdd}{%05d}），与 iot 侧 callNo 组件无复用（IoTDA 直发路径的
 * callNo 语义随联调冻结后可切换为直存）。
 *
 * <p>bedId/patientId 可空偏差照 Task 12 主报告裁定成立照旧：IoTDA 直发路径床号可缺位，落行
 * bed_id/patient_id 随载荷可空。
 *
 * <p><b>确认机制</b>：RabbitMQ 容器 AUTO 确认（宪法 A.5-5）；幂等分域 consumer_module=ward
 * （wardConsumerSupport @Qualifier 定绑——GC7 红线）；归 internal/ 包（宪法 B.1）。
 */
@Slf4j
public class CallTriggeredEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final ObjectMapper objectMapper;

    private final WardCallMapper callMapper;

    private final WardSeqGate seqGate;

    /**
     * 全参构造器（装配归 WardConfig @Import，backend 宪法 B.1；消费模板系 common 基类跨模块
     * 多实例 Bean，必须 @Qualifier 定绑 wardConsumerSupport——GC7 红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 WardMessagingConfig wardConsumerSupport Bean
     * @param objectMapper    JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     * @param callMapper      呼叫行 mapper，非空；设备源呼叫落行通道
     * @param seqGate         业务号发号器，非空；落行签发 call_no
     */
    public CallTriggeredEventListener(
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
     * 设备呼叫触发消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.ward.iot.call.triggered
     */
    @RabbitListener(queues = WardMessagingConstants.QUEUE_IOT_CALL_TRIGGERED)
    public void onCallTriggered(Message message) {
        consumerSupport.consume(message, this::handleCallTriggered);
    }

    /**
     * 消费业务体（包级直驱可测）：载荷契约解析 + 设备源呼叫落行。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 CallTriggeredPayload 契约不符（字段缺失或类型错误）
     *                               ——按消费失败处置（三段式③失败收尾后重抛走死信），禁止静默吞错
     */
    void handleCallTriggered(EventEnvelope envelope) {
        CallTriggeredPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), CallTriggeredPayload.class);
        } catch (JsonProcessingException e) {
            // 载荷不合规（缺字段/类型错）等同业务失败：上抛由三段式③失败收尾（FAILED 留痕后重抛）
            throw new IllegalStateException("设备呼叫触发事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "设备呼叫触发事件受理：callNo={}，deviceId={}，callType={}，wardId={}，event_id={}，traceId={}",
                payload.callNo(),
                payload.deviceId(),
                payload.callType(),
                payload.wardId(),
                envelope.eventId(),
                envelope.traceId());
        String callNo = seqGate.nextCallNo();
        WardCallEntity entity = new WardCallEntity();
        entity.setCallNo(callNo);
        entity.setWardId(payload.wardId());
        entity.setBedId(payload.bedId());
        entity.setDeviceId(payload.deviceId());
        entity.setCallType(CallType.fromCode(payload.callType()));
        entity.setSource(CallSource.IOT);
        entity.setStatus(CallStatus.CREATED);
        entity.setEscalationCount(0);
        // source_ref 承载 iot 侧触发引用（联动回接路径=联动执行号，语义申报见类注释）
        entity.setSourceRef(payload.callNo());
        // 数据库写操作：设备源呼叫落行（事件驱动的自动呼叫；同床位合并语义仅约束人工创建入口）
        callMapper.insert(entity);
        log.info(
                "设备源呼叫已落行：callNo={}，sourceRef={}，wardId={}，bedId={}，deviceId={}",
                callNo,
                payload.callNo(),
                payload.wardId(),
                payload.bedId(),
                payload.deviceId());
    }
}

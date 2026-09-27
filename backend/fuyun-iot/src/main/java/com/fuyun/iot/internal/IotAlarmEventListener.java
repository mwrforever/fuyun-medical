package com.fuyun.iot.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 告警触发自事件消费者（P2 PR-2 Task 9，联动触发源主入口）：q.iot.iot.alarm.triggered 的标准
 * 范式消费执行点——IdempotentConsumerSupport 标准三段式（NX 抢占 → 业务 → PROCESSED 登记），
 * 业务体为联动执行器的 ALARM_TRIGGERED 源编排（条件匹配规则集 → 逐规则执行动作 → 留痕 →
 * 发布 iot.linkage.executed）。
 *
 * <p><b>确认机制（红线 4，锁定决策 6）</b>：本监听器走 RabbitMQ 容器 <b>AUTO 确认</b>（宪法
 * A.5-5：监听方法成功返回即由容器确认，失败有界重试耗尽进 fy.dlx）；幂等分域走
 * MessageIdempotencyService 标准范式（Redis NX 前置 + received_event 唯一索引兜底），与 iot
 * AMQP 主链路明细幂等分域不混用（IotFanoutListener 同口径）。
 *
 * <p><b>at-least-once 语义注记</b>：处理失败（执行器异常/登记失败）不登记 PROCESSED，帧回归
 * broker 重投域——重投后联动规则重新执行并签发新联动号（暂存/成功行可能重复），重复强化推送
 * 与留痕重复无害（at-least-once 域语义，IotFanoutListener 同口径），审计以 linkage_no 去重。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 fuyun-app IotConfig @Import。
 */
@Slf4j
public class IotAlarmEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final LinkageExecutor executor;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；消费模板系 common 基类跨模块
     * 多实例 Bean，fuyun-app 上下文多候选，必须 @Qualifier 定绑 iotConsumerSupport——GC7
     * common 模板类多实例定绑红线；单测按位置构造零改动）。
     *
     * @param consumerSupport 消费模板，非空；定绑 IotMessagingConfig iotConsumerSupport Bean
     * @param executor        联动执行器，非空；ALARM_TRIGGERED 源编排委托点
     * @param objectMapper    JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     */
    public IotAlarmEventListener(
            @Qualifier("iotConsumerSupport") IdempotentConsumerSupport consumerSupport,
            LinkageExecutor executor,
            ObjectMapper objectMapper) {
        this.consumerSupport = consumerSupport;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /**
     * 告警触发事件消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_ALARM_TRIGGERED)
    public void onAlarmTriggered(Message message) {
        consumerSupport.consume(message, this::handleAlarmTriggered);
    }

    /**
     * 消费业务体（包级直驱可测）：载荷契约解析 + 受理留痕 + 联动执行器编排委托。
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
                "告警触发事件受理（联动触发源）：alarmNo={}，deviceId={}，metricCode={}，wardId={}，event_id={}，traceId={}",
                payload.alarmNo(),
                payload.deviceId(),
                payload.metricCode(),
                payload.wardId(),
                envelope.eventId(),
                envelope.traceId());
        executor.onAlarmTriggered(payload);
    }
}

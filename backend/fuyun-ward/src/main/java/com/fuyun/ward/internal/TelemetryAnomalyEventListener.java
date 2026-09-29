package com.fuyun.ward.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.api.payload.TelemetryAnomalyPayload;
import com.fuyun.ward.constants.WardMessagingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 体征采集质量注记消费监听器（P2 PR-2 Task 12 Step 5）：q.ward.iot.telemetry.anomaly
 * （V1004 id 78，payload 契约 TelemetryAnomalyPayload——iot api 面冻结 record）的标准范式
 * 消费执行点，业务体为断流异常注记——按 deviceId 落 Redis 快照（值=载荷 JSON，TTL 24h 自然
 * 过期免清理），体征看板读时出注记。
 *
 * <p>实现形态申报：brief 预案「iot.telemetry.anomaly 消费或查询面——取实现最简者」，本链取
 * <b>消费面</b>（iot 无质量查询端口、禁跨模块读表——实测结论；消费落 Redis 为 ward 域内状态，
 * 无跨模块面新增）。
 *
 * <p><b>确认机制</b>：RabbitMQ 容器 AUTO 确认（宪法 A.5-5）；幂等分域 consumer_module=ward
 * （wardConsumerSupport @Qualifier 定绑——GC7 红线）；归 internal/ 包（宪法 B.1）。
 */
@Slf4j
public class TelemetryAnomalyEventListener {

    private final IdempotentConsumerSupport consumerSupport;

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate redisTemplate;

    /**
     * 全参构造器（装配归 WardConfig @Import，backend 宪法 B.1；JSON 转换器系全局定制实例——
     * Instant 反序列化依赖 jsr310 模块注册）。
     *
     * @param consumerSupport 消费模板，非空；定绑 WardMessagingConfig wardConsumerSupport Bean
     * @param objectMapper    JSON 转换器，非空；载荷契约 record 反序列化与快照序列化
     * @param redisTemplate   String 模板（A.5-1），非空；注记快照写入通道
     */
    public TelemetryAnomalyEventListener(
            @Qualifier("wardConsumerSupport") IdempotentConsumerSupport consumerSupport,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate) {
        this.consumerSupport = consumerSupport;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 遥测断流异常消费入口：标准三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.ward.iot.telemetry.anomaly
     */
    @RabbitListener(queues = WardMessagingConstants.QUEUE_IOT_TELEMETRY_ANOMALY)
    public void onTelemetryAnomaly(Message message) {
        consumerSupport.consume(message, this::handleTelemetryAnomaly);
    }

    /**
     * 消费业务体（包级直驱可测）：载荷契约解析 + deviceId 维度 Redis 注记快照写入。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 TelemetryAnomalyPayload 契约不符——按消费失败处置
     *                               （三段式③失败收尾后重抛走死信），禁止静默吞错
     */
    void handleTelemetryAnomaly(EventEnvelope envelope) {
        TelemetryAnomalyPayload payload;
        try {
            payload = objectMapper.readValue(envelope.payload().toString(), TelemetryAnomalyPayload.class);
        } catch (Exception e) {
            // EX-19 收口 C 类：内部事件契约断言，保留 ISE——上抛由三段式③失败收尾（FAILED 留痕后重抛
            // 走死信），消费失败→重试→死信链路语义不变，零行为变化
            throw new IllegalStateException("遥测断流异常载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        // 缓存操作（写）：deviceId 维度注记快照（值=载荷 JSON，TTL 24h 自然过期——瞬态提示自愈）
        ValueOperations<String, String> operations = redisTemplate.opsForValue();
        String key = WardMessagingConstants.VITAL_ANOMALY_KEY_PREFIX + payload.deviceId();
        try {
            operations.set(key, objectMapper.writeValueAsString(payload), WardMessagingConstants.VITAL_ANOMALY_TTL);
        } catch (Exception e) {
            // EX-19 收口 C 类：内部序列化防御断言（进程内 Jackson 写失败属系统级异常），保留 ISE——
            // 上抛由三段式③失败收尾走死信，零行为变化
            throw new IllegalStateException("遥测断流异常注记序列化失败：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "体征采集质量注记已落快照：deviceId={}，metricCode={}，anomalyType={}，event_id={}，traceId={}",
                payload.deviceId(),
                payload.metricCode(),
                payload.anomalyType(),
                envelope.eventId(),
                envelope.traceId());
    }
}

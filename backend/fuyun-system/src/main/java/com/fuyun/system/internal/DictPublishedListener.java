package com.fuyun.system.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.system.api.DictPublishedPayload;
import com.fuyun.system.constants.SystemMessagingConstants;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 字典发布广播消费者（B3.2 消费侧，BRIEF-PR3-01 §3.2）：q.system.system.dict.published
 * 的标准范式消费执行点。
 *
 * <p>消费姿态：@RabbitListener 注解驱动 + 容器 AUTO 确认（backend 宪法 A.5-5）；raw
 * {@link Message} 承接原始帧（容器工厂 SimpleMessageConverter 兜底，先例 DeadLetterListener/
 * MessagingGovernanceIT）→ UTF-8 解码 → {@link EventEnvelopeCodec#fromJson} 消费侧合规校验
 * （不合规抛 IllegalArgumentException → 有界重试耗尽进 fy.dlx 留痕）。
 *
 * <p>标准幂等范式（含 D-7 回查语义与失败链留痕，MessageIdempotencyService javadoc）：
 * tryAcquire false（回查仅认 PROCESSED 行）→ return 跳过即 AUTO 确认；业务执行 + recordProcessed
 * 成功登记；业务失败 settleFailure 失败收尾（释放前置键 + FAILED 留痕，双保留不遮蔽）后重抛
 * （交容器有界重试，耗尽进 fy.dlx）。
 *
 * <p>消费后动作（P0 占位）：字典类型/版本日志留痕；字典本地缓存失效随 P1 字典缓存实现接入
 * （P0 无缓存实体可失效，禁止为占位引入死代码）。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 SystemMessagingConfig @Import。
 */
@Slf4j
public class DictPublishedListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 SystemMessagingConfig @Import，backend 宪法 B.1）。
     *
     * @param idempotencyService 消费幂等构件，非空；来源：M20 治理构件装配（接口沉 common）
     * @param codec              信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param objectMapper       JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     */
    public DictPublishedListener(
            MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    /**
     * 字典发布事件消费入口：解析信封 → 幂等范式 → 业务留痕 + 成功登记。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     */
    @RabbitListener(queues = SystemMessagingConstants.QUEUE_DICT_PUBLISHED)
    public void onDictPublished(Message message) {
        // 原文进 codec：__TypeId__ 头不作消费依据（CF-1 冻结约定）；不合规信封上抛交有界重试转死信
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), SystemMessagingConstants.MODULE)) {
            log.info(
                    "重复投递跳过：consumerModule={}，event_id={}，eventType={}",
                    SystemMessagingConstants.MODULE,
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        // 信封五要素在业务前构造一次：成功登记与失败留痕共用（两处字段映射不漂移）
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(),
                envelope.eventType(),
                envelope.producer(),
                envelope.occurredAt(),
                SystemMessagingConstants.MODULE);
        try {
            doBusiness(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（W-6③ 双保留，异常链不遮蔽 e），上抛走有界重试进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 消费业务（P0 占位）：载荷契约解析 + 结构化日志留痕。
     *
     * <p>P1 扩展点：字典本地缓存按 typeCode 失效（版本化对账），接入时替换本方法注释标注处，
     * 消费范式与登记语义不变。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与契约不符（dictType/version 缺失或类型错误）——按消费
     *                               失败处置（settleFailure 失败收尾后重抛走死信），禁止静默吞错
     */
    private void doBusiness(EventEnvelope envelope) {
        DictPublishedPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), DictPublishedPayload.class);
        } catch (JsonProcessingException e) {
            // 载荷不合规（缺字段/类型错）等同业务失败：上抛由范式③失败收尾（FAILED 留痕后重抛），最终转死信留痕
            throw new IllegalStateException("字典发布载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "字典发布广播消费完成：dictType={}，version={}，event_id={}，traceId={}",
                payload.dictType(),
                payload.version(),
                envelope.eventId(),
                envelope.traceId());
        // P0 缓存失效占位：字典本地缓存失效随 P1 字典缓存实现接入（cache/DictCacheService），P0 无缓存实体
    }
}

package com.fuyun.integration.internal;

import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 死信监听器：全系统死信统一队列 q.integration.dead-letter 的落库与告警执行点（M20 §5/§10）。
 *
 * <p>消费姿态：@RabbitListener 注解驱动 + 容器 AUTO 确认（backend 宪法 A.5-5，禁手编监听
 * 容器 Bean）；方法签名以 raw {@link Message} 承接——死信可能是毒丸报文，不做 JSON 类型
 * 转换，防转换器二次失败丢消息。
 *
 * <p>处理流程：① x-death 轨迹解析（来源队列/死因/原始路由键）；② 消息体 UTF-8 解码为原文
 * → SHA-256 摘要；③ 经 {@link EventEnvelopeCodec#fromJson} 尝试提取 event_id/event_type，
 * 失败则两列置空且 fail_reason 标注"信封不合规"（M20 红线 1：不合规信封拒收留痕）；
 * ④ 组装 PENDING 行落库（info 日志含 source_queue/event_id/摘要，不打印完整 payload 防
 * 敏感信息入日志）；⑤ 落库失败（含一切运行时异常）catch 后 error 告警且不抛——AUTO 确认
 * 放弃该帧，防毒丸消息在死信队列无限循环（M20 §10"留痕写入失败必须告警"口径）。
 *
 * <p>W-6① 列宽防线：source_queue/routing_key/event_id/event_type/fail_reason 五列落库前按 V4 列宽
 * 截断（TextTruncate，常量集中 constants/）——畸形帧超长字段不得使留痕落库失败。
 *
 * <p>同一死信重复投递会重复落行：dead_letter 无唯一约束（同一 eventId 可因不同消费者多次
 * 死信），P1 死信管理界面完整化时收敛——与 V4 迁移定案口径一致。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 MessagingGovernanceConfig @Import（com.fuyun.integration 不在组件扫描范围）。
 */
@Slf4j
public class DeadLetterListener {

    /** x-death 轨迹条目键：来源队列 */
    private static final String DEATH_KEY_QUEUE = "queue";

    /** x-death 轨迹条目键：死因（消费重试耗尽为 rejected） */
    private static final String DEATH_KEY_REASON = "reason";

    /** x-death 轨迹条目键：原始路由键列表（死信转发保留原路由键，取首条还原） */
    private static final String DEATH_KEY_ROUTING_KEYS = "routing-keys";

    /** 死信摘要算法：SHA-256，十六进制摘要 64 位与 payload_digest 列宽一致 */
    private static final String DIGEST_ALGORITHM_SHA256 = "SHA-256";

    private final DeadLetterMapper deadLetterMapper;

    private final EventEnvelopeCodec eventEnvelopeCodec;

    /**
     * 全参构造器。
     *
     * @param deadLetterMapper    死信台账 mapper，非空；来源：同模块 mapper 包
     * @param eventEnvelopeCodec  信封编解码器，非空；来源：MessagingGovernanceConfig @Import 装配
     */
    public DeadLetterListener(DeadLetterMapper deadLetterMapper, EventEnvelopeCodec eventEnvelopeCodec) {
        this.deadLetterMapper = deadLetterMapper;
        this.eventEnvelopeCodec = eventEnvelopeCodec;
    }

    /**
     * 死信帧统一入口：解析轨迹与信封身份后落 PENDING 台账行，永不向容器抛出。
     *
     * <p>正常返回即 AUTO 确认（含落库失败场景——error 告警后放弃该帧，防死循环）；
     * 本方法不应抛出任何异常（抛出即重入死信队列，与毒丸治理目标相悖）。
     *
     * @param message 死信 raw 消息，非空；来源：fy.dlx 经 "#" 绑定汇入的死信统一队列
     */
    @RabbitListener(queues = MessagingConstants.QUEUE_DEAD_LETTER)
    public void onDeadLetter(Message message) {
        DeathInfo death = extractDeath(message.getMessageProperties());
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String digest = sha256Hex(body);

        String eventId = null;
        String eventType = null;
        String failReason;
        try {
            EventEnvelope envelope = eventEnvelopeCodec.fromJson(body);
            eventId = envelope.eventId();
            eventType = envelope.eventType();
            failReason = "消费死信：reason=" + death.reason();
        } catch (IllegalArgumentException e) {
            // M20 红线 1：不合规信封拒收留痕——身份两列保持空，异常消息（含字段名摘要）作死信原因
            failReason = e.getMessage();
        }

        DeadLetter deadLetter = new DeadLetter();
        // 列宽防线（W-6①）：畸形帧的来源队列/路由键与超长异常消息一律截断后落库，
        // 否则整行写入失败 → 留痕静默丢失，违背「不合规信封拒收留痕」红线
        deadLetter.setSourceQueue(
                TextTruncate.truncate(death.sourceQueue(), MessagingConstants.DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH));
        deadLetter.setRoutingKey(
                TextTruncate.truncate(death.routingKey(), MessagingConstants.DEAD_LETTER_ROUTING_KEY_MAX_LENGTH));
        deadLetter.setEventId(TextTruncate.truncate(eventId, MessagingConstants.DEAD_LETTER_EVENT_ID_MAX_LENGTH));
        deadLetter.setEventType(TextTruncate.truncate(eventType, MessagingConstants.DEAD_LETTER_EVENT_TYPE_MAX_LENGTH));
        deadLetter.setPayloadBody(body);
        deadLetter.setPayloadDigest(digest);
        deadLetter.setFailReason(TextTruncate.truncate(failReason, MessagingConstants.FAIL_REASON_MAX_LENGTH));
        deadLetter.setStatus(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
        try {
            deadLetterMapper.insert(deadLetter);
        } catch (RuntimeException e) {
            // 落库失败必须告警且不抛（M20 §10）：AUTO 确认放弃该帧防毒丸无限循环；
            // catch 范围取 RuntimeException 兜底（覆盖非 DB 意外），error 日志即为告警通道
            log.error(
                    "死信落库失败，该帧放弃转人工排查：source_queue={}，event_id={}，payload_digest={}，原因={}",
                    death.sourceQueue(),
                    eventId,
                    digest,
                    e.getMessage(),
                    e);
            return;
        }
        // 不打印完整 payload 防敏感信息入日志；库内留有原文与摘要，日志以 source_queue/event_id/摘要定位
        log.info(
                "死信留痕落库完成：source_queue={}，event_id={}，event_type={}，payload_digest={}，fail_reason={}",
                death.sourceQueue(),
                eventId,
                eventType,
                digest,
                failReason);
    }

    /**
     * 解析 x-death 死信轨迹首条（最近一次死因）：提取来源队列/死因/原始路由键。
     *
     * @param properties 消息属性，非空；来源：RabbitMQ 服务端注入的 x-death 头
     * @return 死信轨迹三元组；头结构缺失时各字段以空串兜底（source_queue 为落库 NOT NULL 列）
     */
    private DeathInfo extractDeath(MessageProperties properties) {
        Object header = properties.getHeaders().get(MessagingConstants.HEADER_X_DEATH);
        if (header instanceof List<?> deaths && !deaths.isEmpty() && deaths.get(0) instanceof Map<?, ?> death) {
            return new DeathInfo(
                    Objects.toString(death.get(DEATH_KEY_QUEUE), ""),
                    Objects.toString(death.get(DEATH_KEY_REASON), ""),
                    firstRoutingKey(death.get(DEATH_KEY_ROUTING_KEYS)));
        }
        return new DeathInfo("", "", "");
    }

    /**
     * 提取原始路由键首条（=事件类型；死信转发保留原始路由键，据此溯源事件）。
     *
     * @param routingKeys x-death 轨迹条目的 routing-keys 值，可空
     * @return 首条路由键；结构缺失或空列表时返回空串
     */
    private String firstRoutingKey(Object routingKeys) {
        if (routingKeys instanceof List<?> keys && !keys.isEmpty() && keys.get(0) != null) {
            return keys.get(0).toString();
        }
        return "";
    }

    /**
     * 计算消息原文的 SHA-256 十六进制摘要（小写 64 位，供列表页快速比对与篡改检测）。
     *
     * @param body 消息体原文，非空
     * @return 64 位小写十六进制摘要
     */
    private String sha256Hex(String body) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256);
            byte[] hashed = messageDigest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 内置算法，理论不可达；防御性包装为 IllegalState 使环境缺陷显性暴露
            throw new IllegalStateException("SHA-256 摘要算法不可用（JDK 环境异常）", e);
        }
    }

    /**
     * 死信轨迹三元组值对象：x-death 首条解析产物（record 浅不可变，A.1-2）。
     *
     * @param sourceQueue 来源队列（x-death[].queue）
     * @param reason      死因（消费重试耗尽为 rejected）
     * @param routingKey  原始路由键（取 routing-keys 首条）
     */
    private record DeathInfo(String sourceQueue, String reason, String routingKey) {}
}

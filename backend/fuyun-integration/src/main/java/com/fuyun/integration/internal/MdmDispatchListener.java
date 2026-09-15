package com.fuyun.integration.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.MdmDispatchLog;
import com.fuyun.integration.mapper.MdmDispatchLogMapper;
import com.fuyun.integration.service.IMdmSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 主数据分发流水消费者：M01 五个主数据广播事件的消费侧登记点（M20 §6 FU-M20-04「广播链路 =
 * M01 事件 → fy.topic → 各模块缓存刷新，本模块记分发流水」）。
 *
 * <p>消费姿态：容器 AUTO 确认 + raw {@link Message} 承接原文 + 标准幂等范式（{@link
 * MessageIdempotencyService}，后端宪法 A.5-5/A.5-6）；队列名与 IntegrationMdmConfig 经治理构件
 * 声明的队列同源（同一组常量拼接，禁手写队列字面量）。
 *
 * <p>载荷边界（M20 红线 1）：只读取载荷的 {@code version} 治理字段（Spec §4 mdm_dispatch_log
 * 明文列 + §3.3「同步到哪个版本」），不解读其他业务字段；占位 schema 事件（org/user/param/practice）
 * 载荷尚无 version 字段，登记 null 待 M01 实装（PR-3）。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点为
 * IntegrationMdmConfig @Import。
 */
@Slf4j
public class MdmDispatchListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final IMdmSubscriptionService subscriptionService;

    private final MdmDispatchLogMapper dispatchLogMapper;

    /**
     * 全参构造器（装配归 IntegrationMdmConfig @Import）。
     *
     * @param idempotencyService  消费幂等构件，非空；来源：M20 治理构件装配
     * @param codec               信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param subscriptionService 主数据订阅服务，非空；分发目标清单数据源（同模块 service）
     * @param dispatchLogMapper   分发流水 mapper，非空；容器驱动入口按死信监听同款直用 mapper
     */
    public MdmDispatchListener(
            MessageIdempotencyService idempotencyService,
            EventEnvelopeCodec codec,
            IMdmSubscriptionService subscriptionService,
            MdmDispatchLogMapper dispatchLogMapper) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.subscriptionService = subscriptionService;
        this.dispatchLogMapper = dispatchLogMapper;
    }

    /**
     * 主数据广播事件统一消费入口（五个事件共用一套队列声明与处理逻辑）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至 q.integration.* 队列的信封线格式
     */
    @RabbitListener(
            queues = {
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_DICT_PUBLISHED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_ORG_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_USER_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_PARAM_CHANGED,
                MessagingConstants.QUEUE_PREFIX + MessagingConstants.MODULE + "." + MdmConstants.EVENT_PRACTICE_CHANGED
            })
    public void onMasterDataChanged(Message message) {
        // 原文进 codec：__TypeId__ 头不作消费依据（CF-1 冻结约定）
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), MessagingConstants.MODULE)) {
            return;
        }
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(),
                envelope.eventType(),
                envelope.producer(),
                envelope.occurredAt(),
                MessagingConstants.MODULE);
        try {
            doBusiness(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（不遮蔽 e），上抛交容器有界重试耗尽进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 登记一行分发流水：topic 由事件类型推导、version 取载荷治理字段、target_modules 取当前订阅方清单。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 事件类型不在主数据主题映射内（队列绑定与常量漂移）时触发——
     *                               按消费失败处置（上抛走有界重试），禁止静默丢弃
     */
    private void doBusiness(EventEnvelope envelope) {
        String topic = MdmConstants.TOPIC_BY_EVENT_TYPE.get(envelope.eventType());
        if (topic == null) {
            throw new IllegalStateException("事件类型未登记为主数据主题，禁止经本监听器消费：" + envelope.eventType());
        }
        JsonNode versionNode = envelope.payload().path("version");
        Long version = versionNode.isNumber() ? versionNode.asLong() : null;
        List<String> targets = subscriptionService.listSubscriberModules(topic);
        String targetModules =
                TextTruncate.truncate(String.join(",", targets), MessagingConstants.MDM_TARGET_MODULES_MAX_LENGTH);
        MdmDispatchLog logRow = new MdmDispatchLog();
        logRow.setTopic(topic);
        logRow.setVersion(version);
        logRow.setDispatchMode(MdmConstants.DISPATCH_MODE_BROADCAST);
        // dispatched_at 取信封 occurredAt（事件发生即分发；与 received_event.occurred_at 同口径）
        logRow.setDispatchedAt(OffsetDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC));
        logRow.setTargetModules(targetModules);
        dispatchLogMapper.insert(logRow);
        log.info(
                "主数据分发流水登记完成：topic={}，version={}，target_modules={}，event_id={}，traceId={}",
                topic,
                version,
                targetModules,
                envelope.eventId(),
                envelope.traceId());
    }
}

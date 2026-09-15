package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.common.utils.TextTruncate;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.properties.MessagingProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 消息消费幂等服务实现：M20 消费幂等治理构件的两层去重执行点（backend 宪法 A.5-6）。
 *
 * <p>两层语义：① Redis SET NX PX 前置去重（加速层）——键
 * {@code fy:integration:idempotency:<consumerModule>:<eventId>}（A.5-1 冒号分层），TTL 取
 * fuyun.messaging.idempotency-redis-ttl（仅覆盖常态重复投递窗口，非幂等正确性依据），
 * Redis 故障降级放行（故障不得放大为消费不可用）；② received_event 表
 * (event_id, consumer_module) 唯一索引（V3 迁移）最终兜底（正确性保证层）——
 * DuplicateKeyException 按既有行状态分流处置（FAILED 升级 / PROCESSED 幂等跳过，见下段
 * 状态机）；其他 DB 异常原样上抛（真故障必须暴露，交容器有界重试，耗尽进 fy.dlx）。
 *
 * <p>D-7 裁决（消除 TTL 窗口误判丢消息）：NX 抢占失败时不直接判重复——回查 received_event
 * 台账（(event_id, consumer_module) 唯一索引查询），已有 status=PROCESSED 行才跳过；否则
 * （无行 / 仅 FAILED 行）warn 后放行重新处理，保持 at-least-once。
 *
 * <p>失败留痕状态机（Spec §3.2 步骤②④⑤，P1 已落地）：(event_id, consumer_module) 唯一索引行
 * 承载状态机——同一事件同一消费者一行，状态在行内迁移：首次消费失败经 settleFailure 登记 FAILED
 * 行（retry_count=1），有界重试再次失败原子累加 retry_count 并刷新 fail_reason（单语句原子，
 * 多实例安全），重试成功经 recordProcessed 按条件升级为 PROCESSED（清 fail_reason、写
 * processed_at；已是 PROCESSED 行保持首次成功时刻不变，幂等跳过）。tryAcquire 回查已带
 * status=PROCESSED 过滤（D-7 前置强制项落地），FAILED 行不会被误判为已处理而丢重投。
 *
 * <p>无状态单例（多实例部署前提）；登记/留痕均为单条原子语句，无需方法级事务（A.4.2-7 事务
 * 边界以最小开销承载，单语句自原子）。落 service/impl 包 = JaCoCo 核心包 PACKAGE LINE 1.00
 * 覆盖对象（DoD 第 2 条）。
 */
@Slf4j
public class MessageIdempotencyServiceImpl implements MessageIdempotencyService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ReceivedEventMapper receivedEventMapper;

    private final MessagingProperties messagingProperties;

    /**
     * 全参构造器。
     *
     * @param stringRedisTemplate  Redis 字符串模板，非空；来源：Boot 自动装配（Key/Value 均 String 序列化）
     * @param receivedEventMapper  幂等台账 mapper，非空；来源：同模块 mapper 包
     * @param messagingProperties  消息治理配置属性，非空；提供幂等前置键 TTL
     */
    public MessageIdempotencyServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ReceivedEventMapper receivedEventMapper,
            MessagingProperties messagingProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.receivedEventMapper = receivedEventMapper;
        this.messagingProperties = messagingProperties;
    }

    @Override
    public boolean tryAcquire(String eventId, String consumerModule) {
        String key = buildKey(eventId, consumerModule);
        Boolean acquired;
        try {
            // SET NX PX 原子占位：值为 eventId 便于残留键人工排查（占位语义只看键存在性）
            acquired = stringRedisTemplate
                    .opsForValue()
                    .setIfAbsent(key, eventId, messagingProperties.idempotencyRedisTtl());
        } catch (DataAccessException e) {
            // Redis 故障降级放行：唯一索引兜底最终幂等，Redis 故障不得放大为消费不可用
            log.warn(
                    "幂等 Redis 前置键抢占失败，降级放行（唯一索引兜底）：consumer_module={}，event_id={}，原因={}",
                    consumerModule,
                    eventId,
                    e.getMessage());
            return true;
        }
        if (Boolean.TRUE.equals(acquired)) {
            return true;
        }
        // D-7 回查（NX 失败分支）：只认已处理行——P1 起 received_event 会出现 FAILED 行，
        // 必须带 status=PROCESSED 过滤，否则失败行会被误判为已处理而跳过重投（丢消息）
        boolean processed = receivedEventMapper.exists(Wrappers.lambdaQuery(ReceivedEvent.class)
                .eq(ReceivedEvent::getEventId, UUID.fromString(eventId))
                .eq(ReceivedEvent::getConsumerModule, consumerModule)
                .eq(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_PROCESSED));
        if (processed) {
            log.info("重复投递被前置键拦截且回查确认已处理，跳过消费：consumer_module={}，event_id={}", consumerModule, eventId);
            return false;
        }
        // 无 PROCESSED 行（前置键残留 / 仅 FAILED 行的上次失败）：放行重新处理（at-least-once 不因加速层削弱）
        log.warn("幂等前置键残留但台账无已处理行，放行重新处理（上次处理疑似中断）：consumer_module={}，event_id={}", consumerModule, eventId);
        return true;
    }

    @Override
    public void recordProcessed(ReceivedEventRecord record) {
        ReceivedEvent entity = new ReceivedEvent();
        fillEnvelope(entity, record);
        entity.setStatus(MessagingConstants.RECEIVED_STATUS_PROCESSED);
        entity.setProcessedAt(OffsetDateTime.now());
        try {
            receivedEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：前次失败行 → 行内升级为已处理（Spec §3.2 步骤④「成功则置已处理」）；
            // 已是 PROCESSED 行 → 影响 0 行，幂等跳过（不刷新 processed_at，保留首次成功时刻）
            int upgraded = receivedEventMapper.update(
                    null,
                    Wrappers.lambdaUpdate(ReceivedEvent.class)
                            .eq(ReceivedEvent::getEventId, entity.getEventId())
                            .eq(ReceivedEvent::getConsumerModule, entity.getConsumerModule())
                            .eq(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_FAILED)
                            .set(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_PROCESSED)
                            .set(ReceivedEvent::getProcessedAt, entity.getProcessedAt())
                            .setSql("fail_reason = NULL"));
            if (upgraded > 0) {
                log.info(
                        "前次失败重试成功，消费台账行内升级为已处理：consumer_module={}，event_id={}",
                        record.consumerModule(),
                        record.eventId());
            } else {
                log.warn(
                        "重复投递命中 received_event 唯一索引，视为已处理跳过：consumer_module={}，event_id={}",
                        record.consumerModule(),
                        record.eventId());
            }
            return;
        }
        log.info(
                "消费幂等登记完成：consumer_module={}，event_id={}，event_type={}",
                record.consumerModule(),
                record.eventId(),
                record.eventType());
    }

    @Override
    public void settleFailure(ReceivedEventRecord record, RuntimeException businessFailure) {
        // ① 释放前置键：Redis 异常不吞（原「释放失败必须暴露」语义不变），以 suppressed 挂回业务异常
        try {
            releaseKey(record.eventId(), record.consumerModule());
        } catch (RuntimeException releaseFailure) {
            businessFailure.addSuppressed(releaseFailure);
            log.warn(
                    "幂等前置键释放失败（已挂 suppressed，不遮蔽业务异常；D-7 回查仍可放行重投）：consumer_module={}，event_id={}，原因={}",
                    record.consumerModule(),
                    record.eventId(),
                    releaseFailure.getMessage());
        }
        // ② FAILED 消费失败登记（Spec §3.2 步骤⑤）：登记失败同样只挂 suppressed，不禁用重试链路
        try {
            registerFailed(record, businessFailure.getMessage());
        } catch (RuntimeException recordFailure) {
            businessFailure.addSuppressed(recordFailure);
            log.error(
                    "消费失败留痕写入失败（已挂 suppressed，不遮蔽业务异常）：consumer_module={}，event_id={}",
                    record.consumerModule(),
                    record.eventId(),
                    recordFailure);
        }
    }

    /**
     * 失败登记：首次失败插入 FAILED 行（retry_count=1）；重试再失败命中唯一索引时原子累加
     * retry_count 并刷新失败原因（单语句原子，多实例安全）。
     *
     * @param record     信封五要素记录，非空
     * @param failReason 失败原因，可空；按列宽截断后落库
     */
    private void registerFailed(ReceivedEventRecord record, String failReason) {
        String truncatedReason = TextTruncate.truncate(failReason, MessagingConstants.FAIL_REASON_MAX_LENGTH);
        ReceivedEvent entity = new ReceivedEvent();
        fillEnvelope(entity, record);
        entity.setStatus(MessagingConstants.RECEIVED_STATUS_FAILED);
        entity.setFailReason(truncatedReason);
        entity.setRetryCount(1);
        try {
            receivedEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 同一帧重试再次失败：行内原子累加计数（读-改-写会丢更新，禁用）。status=FAILED 守卫防
            // 竞态回退：冲突行若已被并发实例处理成功升级为 PROCESSED，本失败留痕迟到，不得把已处理
            // 行打回 FAILED（否则 D-7 回查将误放行重投造成业务重复执行）
            int accumulated = receivedEventMapper.update(
                    null,
                    Wrappers.lambdaUpdate(ReceivedEvent.class)
                            .eq(ReceivedEvent::getEventId, entity.getEventId())
                            .eq(ReceivedEvent::getConsumerModule, entity.getConsumerModule())
                            .eq(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_FAILED)
                            .set(ReceivedEvent::getStatus, MessagingConstants.RECEIVED_STATUS_FAILED)
                            .set(ReceivedEvent::getFailReason, truncatedReason)
                            .setSql("retry_count = retry_count + 1"));
            if (accumulated > 0) {
                log.info(
                        "消费失败留痕累加：consumer_module={}，event_id={}，原因={}",
                        record.consumerModule(),
                        record.eventId(),
                        truncatedReason);
            } else {
                // 守卫 0 行命中 = 已处理事实成立（失败留痕迟到）：跳过留痕保住台账的 PROCESSED 状态
                log.warn(
                        "消费失败留痕守卫未命中（行已升级为已处理），跳过留痕不回退状态：consumer_module={}，event_id={}",
                        record.consumerModule(),
                        record.eventId());
            }
            return;
        }
        log.info(
                "消费失败留痕登记完成：consumer_module={}，event_id={}，原因={}",
                record.consumerModule(),
                record.eventId(),
                truncatedReason);
    }

    /**
     * 释放前置键原语：Redis 异常原样上抛（暴露语义保留），由 settleFailure 挂 suppressed 收口。
     *
     * @param eventId        事件信封 eventId，非空
     * @param consumerModule 消费者模块域标识，非空
     */
    private void releaseKey(String eventId, String consumerModule) {
        stringRedisTemplate.delete(buildKey(eventId, consumerModule));
    }

    /**
     * 信封五要素 → 实体公共填充（成功登记与失败登记共用，防两处字段映射漂移）；occurredAt 为
     * UTC Instant，台账列 TIMESTAMPTZ 以 UTC 偏移承载。
     *
     * @param entity 目标实体，非空
     * @param record 信封五要素记录，非空
     */
    private static void fillEnvelope(ReceivedEvent entity, ReceivedEventRecord record) {
        entity.setEventId(UUID.fromString(record.eventId()));
        entity.setEventType(record.eventType());
        entity.setProducer(record.producer());
        entity.setOccurredAt(OffsetDateTime.ofInstant(record.occurredAt(), ZoneOffset.UTC));
        entity.setConsumerModule(record.consumerModule());
    }

    /**
     * 构造幂等前置键：{@code fy:integration:idempotency:<consumerModule>:<eventId>}
     * （A.5-1 冒号分层键命名规范）。
     *
     * @param eventId        事件信封 eventId，非空
     * @param consumerModule 消费者模块域标识，非空
     * @return 完整 Redis 键
     */
    private String buildKey(String eventId, String consumerModule) {
        return MessagingConstants.IDEMPOTENCY_KEY_PREFIX + consumerModule + ":" + eventId;
    }
}

package com.fuyun.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
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
 * DuplicateKeyException = 并发重复投递已被他实例处理，吞为已处理；其他 DB 异常原样上抛
 * （真故障必须暴露，交容器有界重试，耗尽进 fy.dlx）。
 *
 * <p>D-7 裁决（消除 TTL 窗口误判丢消息）：NX 抢占失败时不直接判重复——回查 received_event
 * 台账（(event_id, consumer_module) 唯一索引查询），已有已处理行才跳过；无行说明上次处理
 * 中断于业务执行前（前置键残留），warn 后放行重新处理，保持 at-least-once。
 *
 * <p>无状态单例（多实例部署前提）；recordProcessed 为单条原子 INSERT，无需方法级事务
 * （A.4.2-7 事务边界以最小开销承载，单语句自原子）。落 service/impl 包 =
 * JaCoCo 核心包 PACKAGE LINE 1.00 覆盖对象（DoD 第 2 条）。
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
        // D-7 回查（NX 失败分支）：NX 失败不必然是重复投递——上次处理可能中断于业务执行前（前置键残留）。
        // 回查 received_event 台账：(event_id, consumer_module) 唯一索引查询，已有已处理行才确认跳过
        boolean processed = receivedEventMapper.exists(Wrappers.lambdaQuery(ReceivedEvent.class)
                .eq(ReceivedEvent::getEventId, UUID.fromString(eventId))
                .eq(ReceivedEvent::getConsumerModule, consumerModule));
        if (processed) {
            log.info("重复投递被前置键拦截且回查确认已处理，跳过消费：consumer_module={}，event_id={}", consumerModule, eventId);
            return false;
        }
        // 台账无行：前置键残留（TTL 窗口内上次处理未完成即中断），放行重新处理（at-least-once 不因加速层削弱）
        log.warn("幂等前置键残留但台账无已处理行，放行重新处理（上次处理疑似中断）：consumer_module={}，event_id={}", consumerModule, eventId);
        return true;
    }

    @Override
    public void recordProcessed(ReceivedEventRecord record) {
        ReceivedEvent entity = new ReceivedEvent();
        entity.setEventId(UUID.fromString(record.eventId()));
        entity.setEventType(record.eventType());
        entity.setProducer(record.producer());
        // 信封 occurredAt 为 UTC Instant，台账列 TIMESTAMPTZ 以 UTC 偏移承载
        entity.setOccurredAt(OffsetDateTime.ofInstant(record.occurredAt(), ZoneOffset.UTC));
        entity.setConsumerModule(record.consumerModule());
        entity.setStatus(MessagingConstants.RECEIVED_STATUS_PROCESSED);
        entity.setProcessedAt(OffsetDateTime.now());
        try {
            receivedEventMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发重复投递已被他实例处理：吞为已处理，消费方正常返回即 AUTO 确认跳过（唯一索引兜底语义）
            log.warn(
                    "重复投递命中 received_event 唯一索引，视为已处理跳过：consumer_module={}，event_id={}",
                    record.consumerModule(),
                    record.eventId());
            return;
        }
        log.info(
                "消费幂等登记完成：consumer_module={}，event_id={}，event_type={}",
                record.consumerModule(),
                record.eventId(),
                record.eventType());
    }

    @Override
    public void release(String eventId, String consumerModule) {
        String key = buildKey(eventId, consumerModule);
        // 仅业务失败路径调用（标准消费范式 catch 分支）；Redis 异常不在此吞掉——释放失败必须暴露，
        // 静默吞掉会残留前置键，使重投被误判为重复投递
        stringRedisTemplate.delete(key);
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

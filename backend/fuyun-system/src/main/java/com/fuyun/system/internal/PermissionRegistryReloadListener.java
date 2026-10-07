package com.fuyun.system.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.system.api.PermissionMatrixChangedPayload;
import com.fuyun.system.constants.SystemMessagingConstants;
import com.fuyun.system.service.ITokenService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 权限矩阵变更消费者（PR-4F W-96② 变更生效链路，F4 生效语义）：同一类两类消费入口——
 * 治理命名队列单实例幂等消费 + 匿名排他广播队列每实例重载（双 @RabbitListener 设计注记
 * 见两方法级 javadoc）。
 *
 * <p>消费姿态：@RabbitListener 注解驱动 + 容器 AUTO 确认（backend 宪法 A.5-5）；raw
 * {@link Message} 承接原始帧（容器工厂 SimpleMessageConverter 兜底）→ UTF-8 解码 →
 * {@link EventEnvelopeCodec#fromJson} 消费侧合规校验（不合规抛 IllegalArgumentException →
 * 有界重试耗尽进 fy.dlx 留痕）。治理命名队列侧套标准幂等范式（tryAcquire / recordProcessed /
 * settleFailure，DictPublishedListener 同款）；广播入口不经幂等服务（load 幂等清空重建，
 * 重复消费无害，设计注记见方法级）。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 SystemMessagingConfig @Import。
 */
@Slf4j
public class PermissionRegistryReloadListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final ObjectMapper objectMapper;

    private final PermissionRegistry permissionRegistry;

    private final ITokenService tokenService;

    /**
     * 全参构造器（装配归 SystemMessagingConfig @Import，backend 宪法 B.1）。
     *
     * @param idempotencyService 消费幂等构件，非空；来源：M20 治理构件装配（接口沉 common）
     * @param codec              信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param objectMapper       JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     * @param permissionRegistry 权限点登记面，非空；来源：SystemWebConfig @Bean（启动已装载）
     * @param tokenService       令牌服务，非空；来源：SystemWebConfig @Bean（会话键清理通道）
     */
    public PermissionRegistryReloadListener(
            MessageIdempotencyService idempotencyService,
            EventEnvelopeCodec codec,
            ObjectMapper objectMapper,
            PermissionRegistry permissionRegistry,
            ITokenService tokenService) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.permissionRegistry = permissionRegistry;
        this.tokenService = tokenService;
    }

    /**
     * 治理命名队列消费入口（幂等三步范式，DictPublishedListener 同款）：重载 Registry +
     * 清理受影响角色会话键。单实例消费语义（共享队列竞争消费）恰好承载「会话清理只需做一次」。
     */
    /**
     * 治理命名队列消费入口（幂等三步范式，DictPublishedListener 同款）：重载 Registry +
     * 清理受影响角色会话键。单实例消费语义（共享队列竞争消费）恰好承载「会话清理只需做一次」。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     * @throws IllegalStateException 载荷与契约不符（roleCode 缺失或类型错误）——包装修正
     *                               受检解析异常后按业务失败处置（settleFailure 失败收尾后重抛
     *                               走死信），禁止受检异常透出绕过失败收尾（评审 B-I1）
     */
    @RabbitListener(queues = SystemMessagingConstants.QUEUE_PERMISSION_CHANGED)
    public void onPermissionMatrixChanged(Message message) {
        // UTF-8 解码 → codec.fromJson 合规校验 → 幂等三步（tryAcquire 重复跳过 / recordProcessed / settleFailure 重抛）
        // → 业务动作：permissionRegistry.load() + tokenService.evictSessionsByRoles(Set.of(payload.roleCode()))
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
            PermissionMatrixChangedPayload payload;
            try {
                payload = objectMapper.treeToValue(envelope.payload(), PermissionMatrixChangedPayload.class);
            } catch (JsonProcessingException e) {
                // 载荷不合规（缺字段/类型错）等同业务失败：包成 RuntimeException 上抛，由范式③ settleFailure
                // 失败收尾（释放前置键 + FAILED 留痕）后重抛走有界重试进 fy.dlx——受检异常直接透出会脱离
                // catch(RuntimeException) 使失败收尾不执行、坏载荷无台账无死信（评审 B-I1，DictPublishedListener 同款）
                throw new IllegalStateException("权限矩阵变更载荷与契约不符：event_id=" + envelope.eventId(), e);
            }
            permissionRegistry.load();
            int evicted = tokenService.evictSessionsByRoles(Set.of(payload.roleCode()));
            log.info(
                    "权限矩阵变更消费完成：roleCode={}，清理会话计数={}，event_id={}，traceId={}",
                    payload.roleCode(),
                    evicted,
                    envelope.eventId(),
                    envelope.traceId());
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（W-6③ 双保留，异常链不遮蔽 e），上抛走有界重试进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 广播队列消费入口（每实例一份匿名排他队列）：仅幂等重载 Registry。
     * 设计注记：治理命名队列竞争消费下「各实例重载」（F4①）不可达——本方法以 @Queue()
     * 匿名排他队列绑定 fy.topic 实现真广播；队列随实例生灭，不经 QueueGovernor 命名治理
     * （偏差申报：事件登记行 V1120 已落、load() 幂等无重复副作用、失败 catch 后 error 留痕
     * 吞掉即 ack——重载失败降级为旧矩阵继续生效，下一变更或重启自愈）。
     */
    @RabbitListener(
            bindings =
                    @QueueBinding(
                            value = @Queue(),
                            exchange =
                                    @Exchange(
                                            name = SystemMessagingConstants.TOPIC_EXCHANGE,
                                            type = ExchangeTypes.TOPIC),
                            key = SystemMessagingConstants.EVENT_PERMISSION_CHANGED))
    public void onPermissionMatrixBroadcast(Message message) {
        try {
            permissionRegistry.load();
            log.info("权限矩阵广播重载完成（实例本地 Registry）");
        } catch (Exception e) {
            log.error("权限矩阵广播重载失败，本实例维持旧矩阵生效至下一变更或重启", e);
        }
    }
}

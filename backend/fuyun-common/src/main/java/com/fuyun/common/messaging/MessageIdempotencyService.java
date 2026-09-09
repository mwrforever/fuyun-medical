package com.fuyun.common.messaging;

/**
 * 消息消费幂等服务（M20 消费幂等治理构件契约）：接口沉 common、实现由 fuyun-integration
 * 运行时装配（M20 Spec M-3 裁决），各业务模块仅依赖本接口即获得消费幂等能力，零编译期
 * 依赖 fuyun-integration。
 *
 * <p>两层去重缺一不可（backend 宪法 A.5-6）：Redis SET NX PX 前置去重（加速层，故障降级
 * 放行）+ received_event 表 (event_id, consumer_module) 唯一索引最终兜底（正确性保证层，
 * 并发重复投递吞为已处理）。
 *
 * <p>标准消费范式（消费方一律按此编写；PR-3 出现第二个真实消费者时再提炼模板基类，
 * 本契约不做抽象）：
 * <pre>{@code
 * if (!idempotency.tryAcquire(eventId, module)) { return; }   // 重复投递：跳过即 AUTO 确认
 * try {
 *     doBusiness();                                            // 业务执行
 *     idempotency.recordProcessed(record);                     // 成功登记（唯一索引兜底并发）
 * } catch (RuntimeException e) {
 *     idempotency.release(eventId, module);                    // 失败释放前置键，允许重试/重投
 *     throw e;                                                 // 上抛交容器有界重试，耗尽进 fy.dlx
 * }
 * }</pre>
 */
public interface MessageIdempotencyService {

    /**
     * 前置抢占幂等键：Redis {@code SET NX PX} 原子占位（键
     * {@code fy:integration:idempotency:<consumerModule>:<eventId>}，TTL 取
     * fuyun.messaging.idempotency-redis-ttl 配置）。
     *
     * <p>Redis 故障时降级放行（warn 日志 + 返回 true，不抛出）——Redis 故障不得放大为消费
     * 不可用，此时由唯一索引兜底最终幂等。
     *
     * @param eventId        事件信封 eventId（UUID 字符串），非空；来源：消费消息解析出的信封
     * @param consumerModule 消费者模块域标识（如 it），非空；幂等键第二要素（同事件可被多模块消费）
     * @return true=首次投递可执行业务；false=重复投递，消费方直接返回跳过（即 AUTO 确认）
     */
    boolean tryAcquire(String eventId, String consumerModule);

    /**
     * 成功登记：插入 received_event 台账（status=PROCESSED、processed_at=now()）。
     *
     * <p>唯一索引冲突（{@code DuplicateKeyException}）= 并发重复投递已被他实例处理，捕获后
     * warn 且不抛（消费方正常返回即 AUTO 确认跳过）；其他 DB 异常原样上抛（真故障必须暴露，
     * 交容器有界重试，耗尽进 fy.dlx）。
     *
     * @param record 信封五要素登记记录，非空；来源：消费消息解析出的信封字段
     */
    void recordProcessed(ReceivedEventRecord record);

    /**
     * 失败释放前置键：删除 Redis 幂等键，保证重投可重新抢占。
     *
     * <p>仅供业务失败路径调用（标准消费范式的 catch 分支）；业务成功路径禁止释放——
     * 成功后释放会让窗口内重投穿透前置去重。
     *
     * @param eventId        事件信封 eventId（UUID 字符串），非空
     * @param consumerModule 消费者模块域标识，非空
     */
    void release(String eventId, String consumerModule);
}

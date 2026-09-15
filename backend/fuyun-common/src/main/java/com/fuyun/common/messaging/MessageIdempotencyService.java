package com.fuyun.common.messaging;

/**
 * 消息消费幂等服务（M20 消费幂等治理构件契约）：接口沉 common、实现由 fuyun-integration
 * 运行时装配（M20 Spec M-3 裁决），各业务模块仅依赖本接口即获得消费幂等能力，零编译期
 * 依赖 fuyun-integration。
 *
 * <p>两层去重缺一不可（backend 宪法 A.5-6）：Redis SET NX PX 前置去重（加速层，故障降级
 * 放行）+ received_event 表 (event_id, consumer_module) 唯一索引最终兜底（正确性保证层）。
 *
 * <p>D-7 裁决（消除 TTL 窗口误判丢消息）：NX 抢占失败不必然是重复投递——上次处理可能中断于
 * 业务执行前，前置键残留而台账无行。因此 NX 失败时必须回查 received_event 台账：已有
 * <b>status=PROCESSED</b> 行才判定重复（返回 false 跳过）；无 PROCESSED 行（含仅 FAILED 行）
 * 则视为前置键残留/上次失败，放行重新处理（返回 true），保持 at-least-once。
 *
 * <p>失败留痕（Spec §3.2 步骤⑤）：消费失败经 {@link #settleFailure} 登记 FAILED 行（首次）
 * 或原子累加 retry_count（重试再失败）；后续重试成功时 {@link #recordProcessed} 把同一行
 * 升级为 PROCESSED（Spec 步骤④「成功则幂等表置已处理」）。
 *
 * <p>标准消费范式（消费方一律按此编写；PR-3 出现第二个真实消费者时再提炼模板基类，本契约
 * 不做抽象）：
 * <pre>{@code
 * ReceivedEventRecord record = new ReceivedEventRecord(
 *         envelope.eventId(), envelope.eventType(), envelope.producer(), envelope.occurredAt(), module);
 * if (!idempotency.tryAcquire(record.eventId(), module)) { return; }   // 重复投递：跳过即 AUTO 确认
 * try {
 *     doBusiness();                                                   // 业务执行
 *     idempotency.recordProcessed(record);                            // 成功登记（唯一索引兜底并发）
 * } catch (RuntimeException e) {
 *     idempotency.settleFailure(record, e);                           // 释放前置键 + FAILED 留痕（不遮蔽 e）
 *     throw e;                                                        // 上抛交容器有界重试，耗尽进 fy.dlx
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
     * <p>D-7 回查语义（NX 失败分支）：回查 received_event 台账且仅认 status=PROCESSED 行——
     * 有 PROCESSED 行 → 返回 false（确认已处理）；否则（无行 / 仅 FAILED 行）→ warn 后返回
     * true 放行重新处理。
     *
     * @param eventId        事件信封 eventId（UUID 字符串），非空；来源：消费消息解析出的信封
     * @param consumerModule 消费者模块域标识（如 it），非空；幂等键第二要素（同事件可被多模块消费）
     * @return true=可执行业务；false=确认重复投递，消费方直接返回跳过（即 AUTO 确认）
     */
    boolean tryAcquire(String eventId, String consumerModule);

    /**
     * 成功登记：插入 received_event 台账（status=PROCESSED、processed_at=now()）。
     *
     * <p>唯一索引冲突（{@code DuplicateKeyException}）分两种：①既有行 status=FAILED（前次失败后
     * 重试成功）→ 行内升级为 PROCESSED（清 fail_reason、写 processed_at）；②既有行已 PROCESSED
     * （并发重复投递）→ warn 幂等跳过，不刷新 processed_at（保留首次成功时刻）。
     * 其他 DB 异常原样上抛（真故障必须暴露，交容器有界重试，耗尽进 fy.dlx）。
     *
     * @param record 信封五要素登记记录，非空；来源：消费消息解析出的信封字段
     */
    void recordProcessed(ReceivedEventRecord record);

    /**
     * 失败收尾（标准消费范式 catch 分支的唯一调用点）：释放前置键 + 登记消费失败留痕（FAILED）。
     *
     * <p>双保留语义（W-6③）：释放前置键的 Redis 异常与失败留痕的 DB 异常都不再上抛，也不吞没，
     * 一律 {@code businessFailure.addSuppressed(...)} 挂回——原始业务异常保持主异常地位，交容器
     * 有界重试耗尽进 fy.dlx；异常链完整保留便于排障（原设计「释放失败必须暴露」的诉求由
     * suppressed + warn 日志共同满足）。
     *
     * <p>本方法自身不抛出（除 {@code record == null} 之类的编程错误由 JVM 抛出）。
     *
     * @param record          信封五要素记录，非空；FAILED 行的 event_id/consumer_module 取自本对象
     * @param businessFailure 原始业务异常，非空；作为主异常保留被挂 suppressed 与上抛
     */
    void settleFailure(ReceivedEventRecord record, RuntimeException businessFailure);
}

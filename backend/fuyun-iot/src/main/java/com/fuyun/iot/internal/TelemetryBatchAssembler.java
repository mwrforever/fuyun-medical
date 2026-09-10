package com.fuyun.iot.internal;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.ITelemetryIngestService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 遥测攒批器：AMQP 消费链与落库事务之间的有界缓冲层（BRIEF-PR4-01 §1.3 攒批写库 + §3 internal 行）。
 *
 * <p>IoTDA 服务端仅缓存 24h/1GB 积压（宪法 A.5-10），本地必须"快速落库 + 批量化"：消费线程解析出
 * 遥测帧后经 {@link #put} 投入有界内存队列（容量 = fuyun.iot.amqp.batch-queue-capacity，默认 5000，
 * <b>满则阻塞等待背压</b>——prefetch 自然限流，防消费速率放大击穿本地内存），单 flush 线程按
 * <b>攒够 batch-size（默认 500）或批首消息起算超 batch-flush-interval（默认 2s）</b>两种条件之一
 * 取出整批交 {@link ITelemetryIngestService#ingest}（方法级独立事务，宪法 A.4.2-7）。
 *
 * <p><b>确认回调时机（两套确认机制之客户端确认侧，锁定决策 6）</b>：本攒批器不直接触碰 JMS——
 * 消费方为每帧附一个 ack 回调（对 {@code message.acknowledge()} 的包装）；落库<b>成功后仅执行批末
 * 条目的回调</b>：JMS Session.CLIENT_ACKNOWLEDGE 为会话级累计确认，确认批末消息即统一确认本批
 * 全部（同会话此前已消费消息一并落账）；<b>落库失败零回调</b>——不确认的帧由 IoTDA 重推，
 * at-least-once 语义由 iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 兜底幂等。这与 RabbitMQ
 * @RabbitListener 容器 AUTO 确认（监听方法成功返回即确认，宪法 A.5-5）互不相干。
 *
 * <p>保序与并发口径：单 flush 线程按接收序提交整批 = 同设备帧天然保序落库；设备哈希多线程分片
 * 属 P1 压测演进项（宪法 A.5-8），P0 简化理由：单写线程吞吐已满足遥测量级且避免分片重排复杂度。
 * 失败处理：ingest 或 ack 回调抛出仅累计 {@link #flushFailureCount}（B4.3 Micrometer 绑定观测）
 * 并 error 告警，<b>绝不抛出 flush 线程</b>（连接层故障归消费者 supervisor，本层只保数据管道存活）。
 *
 * <p>生命周期：实现 SmartLifecycle（phase=1，先于消费者启动、后于消费者停止——宪法 A.5-15 停机
 * 顺序"先停拉取再排空在途批"：消费者 phase=0 先停拉取，本类 stop 排空滞留批后才关线程）。
 * 归 internal/ 包：容器驱动链路的模块内组件，禁止外部引用（宪法 B.1），装配归 IotAmqpConfig @Import。
 */
@Slf4j
public class TelemetryBatchAssembler implements SmartLifecycle {

    /** 停机等待 flush 线程退出的上限：批量落库事务最长耗时远低于此值，超时按异常停机告警处置 */
    private static final long STOP_AWAIT_SECONDS = 30L;

    /** 批内挂起条目：遥测消息 + 其客户端确认回调（批末条目回调执行 = 会话级统一确认本批全部） */
    private record PendingTelemetry(StandardTelemetryMessage message, Runnable ackAction) {}

    /** 遥测入库服务：整批提交的唯一落库通道（方法级独立事务在实现类内承担） */
    private final ITelemetryIngestService ingestService;

    /** 攒批参数（batchSize/batchFlushInterval/batchQueueCapacity），来源：fuyun.iot.amqp.* 绑定 */
    private final IotProperties.Amqp amqp;

    /** 有界挂起队列：满则 put 阻塞等待背压（ArrayBlockingQueue 单锁公平性足够 P0 单消费线程） */
    private final ArrayBlockingQueue<PendingTelemetry> pendingQueue;

    /** flush 线程运行标记：start/stop CAS 闸门，兼作消费循环的停机信号 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 落库/确认失败累计计数（B4.3 Micrometer counter 绑定的观测载体，此处先落业务语义） */
    private final AtomicLong flushFailureCount = new AtomicLong(0);

    /** 单 flush 线程池（命名、有界、随上下文关闭，宪法 B.3-4 线程池纪律） */
    private volatile ExecutorService flushExecutor;

    /**
     * 全参构造器（装配归 IotAmqpConfig @Import，backend 宪法 B.1）。
     *
     * @param ingestService 遥测入库服务，非空；来源：IotConfig 装配链
     * @param properties    IoT 配置属性，非空；本类仅消费其 amqp() 攒批三参数
     */
    public TelemetryBatchAssembler(ITelemetryIngestService ingestService, IotProperties properties) {
        this.ingestService = ingestService;
        this.amqp = properties.amqp();
        this.pendingQueue = new ArrayBlockingQueue<>(this.amqp.batchQueueCapacity());
    }

    /**
     * 投入一条遥测帧（消费线程调用）：有界队列满则阻塞等待背压。
     *
     * <p>背压语义：IoTDA 端 queuePrefetch（默认 1000）已限流在途帧，本队列打满说明落库速率
     * 跟不上消费速率，阻塞消费线程是最安全的天然节流（帧不丢、不 ack、无内存放大）。
     *
     * @param message   已解析的标准遥测消息，非空；来源：TelemetryFrameParser 遥测帧产物
     * @param ackAction 该帧的客户端确认回调（message::acknowledge 的异常适配包装），非空；
     *                  仅当本条为批次末条且落库成功时才会执行
     * @throws InterruptedException 停机中断（put 等待中被 stop 打断）；调用方应停止投递并将
     *                              该帧留待 broker 重推（不 ack），禁吞中断标记
     */
    public void put(StandardTelemetryMessage message, Runnable ackAction) throws InterruptedException {
        pendingQueue.put(new PendingTelemetry(message, ackAction));
    }

    /**
     * 落库/确认失败累计计数（观测载体）。
     *
     * @return 失败次数的原子载体，非空；B4.3 IotAmqpMetrics 将以此绑定 Micrometer counter
     */
    AtomicLong flushFailureCount() {
        return flushFailureCount;
    }

    /**
     * 启动单 flush 线程（SmartLifecycle，phase=1 先于消费者）：线程命名 iot-telemetry-batch-flush。
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        flushExecutor =
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "iot-telemetry-batch-flush"));
        flushExecutor.submit(this::flushLoop);
        log.info(
                "遥测攒批器已启动：batchSize={}，flushInterval={}ms，queueCapacity={}",
                amqp.batchSize(),
                amqp.batchFlushInterval().toMillis(),
                amqp.batchQueueCapacity());
    }

    /**
     * 停机并排空在途批（SmartLifecycle，phase=1 后于消费者停止）：以中断为停机信号打断 flush 线程
     * ——flush 循环被中断时<b>先把已收集的部分批刷出再退出</b>（在途数据不丢，确认回调照常执行，
     * IoTDA 不重推已落库帧）；随后由停机调用线程兜底排空队列内残余条目。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ExecutorService executor = flushExecutor;
        if (executor != null) {
            // shutdownNow 打断阻塞中的 poll：flush 循环刷出部分批后以中断退出，残余排空由本方法兜底
            executor.shutdownNow();
            awaitExecutorTermination(executor);
        }
        drainRemaining();
        log.info("遥测攒批器已停机：落库/确认失败累计={}", flushFailureCount.get());
    }

    /** 启动顺序契约：phase=1 保证先于消费者（phase=0）启动、后于其停止（宪法 A.5-15 停机顺序）。 */
    @Override
    public int getPhase() {
        return 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 运行态标记：flush 线程存续期间为 true（start/stop CAS 同源，供生命周期处理器校验）。 */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * flush 主循环（中断驱动）：批首消息起算时间窗，攒够 batchSize 或窗口到期即整批刷出。
     *
     * <p>循环骨架：空闲期以 flushInterval 为步长轮询（兼作窗口到期检测）→ 取到批首条目后进入
     * 收集窗口（剩余窗口时间内非阻塞收取至 batchSize 上限）→ 整批落库。停机信号 = 线程中断：
     * 空闲期被中断直接退出（排空交 stop()）；收集/落库期被中断<b>先刷出部分批再退出</b>，
     * 杜绝已出队条目因停机丢失。
     */
    private void flushLoop() {
        List<PendingTelemetry> batch = new ArrayList<>(amqp.batchSize());
        while (!Thread.currentThread().isInterrupted()) {
            PendingTelemetry first;
            try {
                first = pendingQueue.poll(amqp.batchFlushInterval().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // 停机信号且本线程未持有批次：恢复中断标记退出，滞留条目由 stop() 兜底排空
                Thread.currentThread().interrupt();
                break;
            }
            if (first == null) {
                continue;
            }
            batch.add(first);
            try {
                long windowEndNanos =
                        System.nanoTime() + amqp.batchFlushInterval().toNanos();
                while (batch.size() < amqp.batchSize()) {
                    long remainingNanos = windowEndNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        // 时间窗触发：批首起算超 flushInterval，未攒满亦刷批（低流量不停批）
                        break;
                    }
                    PendingTelemetry next = pendingQueue.poll(remainingNanos, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                flushBatch(batch);
            } catch (InterruptedException e) {
                // 收集期停机：已出队部分批必须先刷出（在途数据不丢），随后以中断态退出
                Thread.currentThread().interrupt();
                flushBatch(batch);
                break;
            } finally {
                batch.clear();
            }
        }
    }

    /**
     * 整批落库 + 批末统一确认：成功后仅执行批末条目 ack 回调（会话级累计确认覆盖本批全部）；
     * 任何异常（ingest 事务回滚 / ack 回调 JMS 故障）只累计失败计数与 error 告警，不抛出 flush 线程。
     *
     * @param batch 本轮收集的挂起条目（按接收序），可为空列表（空批直接跳过不触库）
     */
    private void flushBatch(List<PendingTelemetry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<StandardTelemetryMessage> messages =
                batch.stream().map(PendingTelemetry::message).toList();
        try {
            int inserted = ingestService.ingest(messages);
            // 批末确认：CLIENT_ACKNOWLEDGE 会话级累计语义，确认批末 = 统一确认本批全部已消费消息
            batch.get(batch.size() - 1).ackAction().run();
            log.info("遥测批落库并统一确认完成：batchSize={}，inserted={}", batch.size(), inserted);
        } catch (Exception e) {
            // 失败零确认：本批帧不 ack，IoTDA 重推后由唯一约束冲突忽略兜底幂等；flush 线程必须存活
            flushFailureCount.incrementAndGet();
            log.error("遥测批落库失败，本批不确认待 IoTDA 重推：batchSize={}，原因={}", batch.size(), e.getMessage(), e);
        }
    }

    /** 停机排空：将滞留条目按 batchSize 分批刷出（复用 flushBatch 的成功确认与失败容错语义）。 */
    private void drainRemaining() {
        List<PendingTelemetry> batch = new ArrayList<>(amqp.batchSize());
        while (!pendingQueue.isEmpty()) {
            PendingTelemetry entry = pendingQueue.poll();
            if (entry == null) {
                break;
            }
            batch.add(entry);
            if (batch.size() >= amqp.batchSize()) {
                flushBatch(batch);
                batch.clear();
            }
        }
        flushBatch(batch);
    }

    /** 等待 flush 线程退出：超时未退出按异常停机告警（不阻塞停机链，线程为非守护随 JVM 处置）。 */
    private void awaitExecutorTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(STOP_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("遥测攒批 flush 线程停机等待超时（{}s），移交 JVM 生命周期处置", STOP_AWAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("遥测攒批器停机等待被中断，移交 JVM 生命周期处置");
        }
    }
}

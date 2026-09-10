package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.ITelemetryIngestService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 遥测攒批器单测（BRIEF-PR4-01 §3 internal/TelemetryBatchAssembler 行）。
 *
 * <p>业务意图：验证攒批写库四类契约——①条数阈值触发（攒够 batchSize 即刷批）；②时间窗触发
 * （批首消息起算超 batchFlushInterval 未攒满也刷批，IoTDA 低流量不停批）；③满容量背压（有界队列
 * 打满后消费侧阻塞等待，防 IoTDA 24h/1GB 缓存被本地内存放大击穿）；④确认回调语义（落库成功后
 * 仅批末消息的 ack 回调执行——JMS CLIENT_ACKNOWLEDGE 会话级累计确认；落库失败零回调，IoTDA 重推
 * 由唯一约束兜底幂等）。另覆盖 stop 排空在途批（宪法 A.5-15 停机顺序）。测试批次参数取亚秒级
 * 小值避免真实长等待，与任何生产默认值无关。
 */
class TelemetryBatchAssemblerTest {

    /** 测试用攒批参数：batchSize=3/interval=50ms/容量=4，亚秒级收敛便于轮询断言 */
    private static final IotProperties.Amqp TEST_AMQP = new IotProperties.Amqp(
            true,
            "amqp://127.0.0.1:5672",
            "test-access-key",
            "test-access-secret",
            List.of("it.iot.telemetry"),
            1000,
            3,
            Duration.ofMillis(50),
            4,
            Duration.ofMillis(10),
            Duration.ofMillis(20));

    /** 测试轮询等待上限：攒批链路为异步线程，2s 上限容忍 Windows 调度抖动（触发窗口仅亚秒级） */
    private static final long AWAIT_MILLIS = 2000L;

    private ITelemetryIngestService ingestService;

    private TelemetryBatchAssembler assembler;

    @BeforeEach
    void setUp() {
        ingestService = mock(ITelemetryIngestService.class);
        assembler = new TelemetryBatchAssembler(ingestService, propsOf(TEST_AMQP));
        assembler.start();
    }

    @AfterEach
    void tearDown() {
        assembler.stop();
    }

    @Test
    @DisplayName("条数触发：攒够 batchSize 即整批落库，且仅批末消息的确认回调执行（会话级统一确认）")
    void flushesBatchWhenCountThresholdReached() throws Exception {
        Runnable firstAck = mock(Runnable.class);
        Runnable lastAck = mock(Runnable.class);
        assembler.put(telemetry("dev-1"), firstAck);
        assembler.put(telemetry("dev-1"), mock(Runnable.class));
        assembler.put(telemetry("dev-1"), lastAck);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StandardTelemetryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService, timeout(AWAIT_MILLIS)).ingest(captor.capture());
        assertThat(captor.getValue()).as("整批按接收序提交落库").hasSize(3);
        // 客户端确认契约：只有批末消息执行 acknowledge——JMS 会话内累计确认覆盖本批全部
        verify(lastAck, timeout(AWAIT_MILLIS)).run();
        verify(firstAck, timeout(AWAIT_MILLIS).times(0)).run();
    }

    @Test
    @DisplayName("时间窗触发：不足 batchSize 时批首消息起算超 flushInterval 亦刷批（低流量不停批）")
    void flushesBatchWhenTimeIntervalElapsed() throws Exception {
        Runnable ack = mock(Runnable.class);
        // 仅 1 条远低于 batchSize=3：时间窗（50ms）到期后必须落库，不得滞留内存
        assembler.put(telemetry("dev-slow"), ack);

        verify(ingestService, timeout(AWAIT_MILLIS)).ingest(anyList());
        verify(ack, timeout(AWAIT_MILLIS)).run();
    }

    @Test
    @DisplayName("满容量背压：有界队列打满后 put 阻塞，flush 启动腾出容量后解除阻塞（防本地内存放大击穿 IoTDA 缓存）")
    void putBlocksWhenBoundedQueueFullThenReleasesAfterFlush() throws Exception {
        // flush 线程未启动时投递：容量 1 被 put#1 占满，put#2 的阻塞与解除完全确定（无消费竞速）；
        // 断言口径 = 全部条目无丢失落库（批内切分随调度而定，不绑定内部刷批次数）
        List<StandardTelemetryMessage> ingested = new CopyOnWriteArrayList<>();
        TelemetryBatchAssembler tinyAssembler =
                new TelemetryBatchAssembler(collectingService(ingested), propsOf(capacityOneProps()));
        tinyAssembler.put(telemetry("dev-full"), mock(Runnable.class));
        CountDownLatch secondPutReturned = new CountDownLatch(1);
        AtomicReference<Exception> putFailure = new AtomicReference<>();
        Thread blockingThread = new Thread(() -> {
            try {
                tinyAssembler.put(telemetry("dev-full"), mock(Runnable.class));
            } catch (InterruptedException e) {
                putFailure.set(e);
            } finally {
                secondPutReturned.countDown();
            }
        });
        blockingThread.start();
        // 阶段一：无 flush 消费时第二条 put 必须真实阻塞（300ms 内不返回即为背压证据）
        assertThat(secondPutReturned.await(300, TimeUnit.MILLISECONDS))
                .as("队列已满且无消费：put 必须阻塞等待背压而非溢出/丢弃")
                .isFalse();
        // 阶段二：启动 flush 后首批刷出腾出容量，阻塞的 put 解除且两条全部落库
        tinyAssembler.start();
        try {
            assertThat(secondPutReturned.await(3, TimeUnit.SECONDS))
                    .as("背压解除：flush 腾出容量后阻塞的 put 必须完成")
                    .isTrue();
            assertThat(putFailure.get()).as("背压等待属正常语义，不应以中断收场").isNull();
            assertThat(awaitIngestedTotal(ingested, 2))
                    .as("背压解除后两条条目必须全部落库（零丢失）")
                    .isTrue();
        } finally {
            tinyAssembler.stop();
        }
    }

    @Test
    @DisplayName("落库失败不确认：ingest 抛异常时零 ack 回调、失败计数递增，且 flush 线程存活可处理下一批")
    void skipsAckAndSurvivesWhenIngestFails() throws Exception {
        doThrow(new IllegalStateException("数据库瞬断")).when(ingestService).ingest(anyList());
        Runnable ack = mock(Runnable.class);
        assembler.put(telemetry("dev-fail"), ack);
        assembler.put(telemetry("dev-fail"), mock(Runnable.class));
        assembler.put(telemetry("dev-fail"), mock(Runnable.class));
        verify(ingestService, timeout(AWAIT_MILLIS)).ingest(anyList());
        assertThat(failureCountEventually(assembler, 1))
                .as("失败计数必须递增（B4.3 指标绑定的观测载体）")
                .isTrue();
        verifyNoInteractions(ack);

        // flush 线程存活契约：失败不抛出线程，落库恢复后下一批照常落库并确认
        doReturn(1).when(ingestService).ingest(anyList());
        Runnable recoveredAck = mock(Runnable.class);
        assembler.put(telemetry("dev-recovered"), recoveredAck);
        assembler.put(telemetry("dev-recovered"), mock(Runnable.class));
        assembler.put(telemetry("dev-recovered"), recoveredAck);
        verify(recoveredAck, timeout(AWAIT_MILLIS)).run();
        assertThat(assembler.flushFailureCount().get()).as("恢复后的成功批次不累计失败").isEqualTo(1L);
    }

    /**
     * 轮询等待失败计数达到期望值（失败计数在 ingest 调用返回后的同一异步链路递增，存在微小相位差）。
     *
     * @param assembler 被测攒批器，非空
     * @param expected  期望失败次数
     * @return true=在等待上限内达标
     */
    private static boolean failureCountEventually(TelemetryBatchAssembler assembler, long expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (assembler.flushFailureCount().get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return assembler.flushFailureCount().get() == expected;
    }

    @Test
    @DisplayName("stop 排空在途批：未达触发条件的滞留条目在停机时全部落库且确认回调执行（不丢在途数据）")
    void drainsPendingEntriesOnStop() throws Exception {
        // 长时间窗 + 大 batchSize：保证停机前不存在自动刷批；断言口径 = 条目零丢失 + 批末确认执行
        // （停机中断可能把滞留条目切分为部分批 + 排空批两次提交，业务结果等价，不绑定内部提交次数）
        List<StandardTelemetryMessage> ingested = new CopyOnWriteArrayList<>();
        TelemetryBatchAssembler drainingAssembler =
                new TelemetryBatchAssembler(collectingService(ingested), propsOf(longWindowProps()));
        drainingAssembler.start();
        Runnable lastAck = mock(Runnable.class);
        drainingAssembler.put(telemetry("dev-drain-1"), mock(Runnable.class));
        drainingAssembler.put(telemetry("dev-drain-2"), lastAck);

        drainingAssembler.stop();

        assertThat(awaitIngestedTotal(ingested, 2)).as("停机排空必须提交全部在途条目（零丢失）").isTrue();
        assertThat(ingested)
                .extracting(StandardTelemetryMessage::deviceId)
                .containsExactly("dev-drain-1", "dev-drain-2");
        verify(lastAck, timeout(AWAIT_MILLIS)).run();
    }

    @Test
    @DisplayName("空批防御：未投递任何条目时 stop 不触发落库调用（避免空批次触库）")
    void stopWithoutPendingEntriesSkipsIngest() {
        TelemetryBatchAssembler idleAssembler = new TelemetryBatchAssembler(ingestService, propsOf(TEST_AMQP));
        idleAssembler.start();
        idleAssembler.stop();
        verifyNoInteractions(ingestService);
    }

    /** 构造标准遥测消息（测试载荷：心率 72，UTC 当前时刻） */
    private static StandardTelemetryMessage telemetry(String deviceId) {
        return new StandardTelemetryMessage(deviceId, "vital.heart-rate", "72", "bpm", Instant.now(), "GOOD", "IOTDA");
    }

    /** 以嵌套 Amqp 构造完整 IotProperties（fallback 未启用，token 传 null 合法） */
    private static IotProperties propsOf(IotProperties.Amqp amqp) {
        return new IotProperties(amqp, new IotProperties.Fallback(null));
    }

    /** 容量 1 的攒批参数：batchSize=2/interval=60ms，专供背压场景 */
    private static IotProperties.Amqp capacityOneProps() {
        return new IotProperties.Amqp(
                true,
                "amqp://127.0.0.1:5672",
                "test-access-key",
                "test-access-secret",
                List.of("it.iot.telemetry"),
                1000,
                2,
                Duration.ofMillis(60),
                1,
                Duration.ofMillis(10),
                Duration.ofMillis(20));
    }

    /** 长时间窗 + 大 batchSize 的攒批参数：专供停机排空场景（停机前杜绝自动刷批） */
    private static IotProperties.Amqp longWindowProps() {
        return new IotProperties.Amqp(
                true,
                "amqp://127.0.0.1:5672",
                "test-access-key",
                "test-access-secret",
                List.of("it.iot.telemetry"),
                1000,
                10,
                Duration.ofHours(1),
                16,
                Duration.ofMillis(10),
                Duration.ofMillis(20));
    }

    /** 落库收集桩：按提交序累积全部批次条目，供"零丢失"业务断言（不绑定内部提交次数） */
    private static ITelemetryIngestService collectingService(List<StandardTelemetryMessage> sink) {
        return batch -> {
            sink.addAll(batch);
            return batch.size();
        };
    }

    /**
     * 轮询等待落库收集总数达到期望值（异步刷批链路的确定性等待）。
     *
     * @param sink     收集容器，非空
     * @param expected 期望落库总条数
     * @return true=在等待上限内达标；false=超时未达标（调用方断言失败）
     */
    private static boolean awaitIngestedTotal(List<StandardTelemetryMessage> sink, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (sink.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return sink.size() == expected;
    }
}

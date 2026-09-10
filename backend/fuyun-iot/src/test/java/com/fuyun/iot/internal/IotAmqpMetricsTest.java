package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuyun.iot.properties.IotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AMQP 双指标绑定单元测试（BRIEF-PR4-01 §4 internal/IotAmqpMetrics 行：三 gauge + 双 counter
 * 以消费者/攒批器既有 Atomic 载体绑定，gauge 弱引用注册由本类强引用防 GC 失效）。
 *
 * <p>覆盖：connected 0/1 直读、断链持续秒数（起点非 0 按固定时钟换算、起点 0 恒为 0）、
 * 攒批队列填充率（深度/容量）、supervisor 重建累计与攒批失败累计 counter 直读。载体 mock 于
 * 同包（accessor 为包内可见）；真实断链指标联动归 IotAmqpReconnectIT（B4.4）。
 */
class IotAmqpMetricsTest {

    /** 固定时钟（断链时长换算基准，可预置） */
    private static final Instant FIXED_NOW = Instant.parse("2026-09-10T08:00:00Z");

    /** 测试容量：填充率 = 深度/容量 断言基准 */
    private static final int QUEUE_CAPACITY = 100;

    private SimpleMeterRegistry registry;

    private IotAmqpTelemetryConsumer consumer;

    private TelemetryBatchAssembler assembler;

    private final AtomicLong connectedFlag = new AtomicLong(0);

    private final AtomicLong disconnectSinceMillis = new AtomicLong(0);

    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        consumer = mock(IotAmqpTelemetryConsumer.class);
        assembler = mock(TelemetryBatchAssembler.class);
        when(consumer.connectedFlag()).thenReturn(connectedFlag);
        when(consumer.disconnectSinceMillis()).thenReturn(disconnectSinceMillis);
        when(consumer.reconnectCount()).thenReturn(reconnectCount);
        when(assembler.pendingQueueSize()).thenReturn(25);
        when(assembler.flushFailureCount()).thenReturn(new AtomicLong(2));
        // 构造即注册：gauge/counter 注册发生在构造器（弱引用注册 + 本类强引用载体的装配契约）
        new IotAmqpMetrics(
                registry,
                consumer,
                assembler,
                propertiesWithCapacity(QUEUE_CAPACITY),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("connected gauge：直读载体值（0=断链 1=连接正常）")
    void exposesConnectedGaugeFromCarrier() {
        assertThat(gaugeValue(IotAmqpMetrics.GAUGE_CONNECTED)).isZero();

        connectedFlag.set(1);

        assertThat(gaugeValue(IotAmqpMetrics.GAUGE_CONNECTED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("断链时长 gauge：起点非 0 按时钟差换算秒数，起点 0（连接正常）恒为 0")
    void computesDisconnectDurationInSecondsFromAnchor() {
        disconnectSinceMillis.set(FIXED_NOW.toEpochMilli() - 10_000L);

        assertThat(gaugeValue(IotAmqpMetrics.GAUGE_DISCONNECT_SECONDS))
                .as("断链 10 秒换算为 10.0")
                .isEqualTo(10.0);

        disconnectSinceMillis.set(0);

        assertThat(gaugeValue(IotAmqpMetrics.GAUGE_DISCONNECT_SECONDS))
                .as("连接正常时断链时长回零")
                .isZero();
    }

    @Test
    @DisplayName("攒批队列填充率 gauge：深度/容量比值（本地积压水位口径）")
    void computesBatchQueueFillRatio() {
        assertThat(gaugeValue(IotAmqpMetrics.GAUGE_QUEUE_FILL_RATIO))
                .as("深度 25 / 容量 100 = 0.25")
                .isEqualTo(0.25);
    }

    @Test
    @DisplayName("supervisor 重建 counter：直读累计计数载体")
    void exposesReconnectCounterFromCarrier() {
        assertThat(counterValue(IotAmqpMetrics.COUNTER_RECONNECT)).isZero();

        reconnectCount.set(5);

        assertThat(counterValue(IotAmqpMetrics.COUNTER_RECONNECT)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("攒批失败 counter：直读失败累计载体（落库/确认失败观测）")
    void exposesFlushFailureCounterFromCarrier() {
        assertThat(counterValue(IotAmqpMetrics.COUNTER_FLUSH_FAILURE)).isEqualTo(2.0);
    }

    /** 读 gauge 当前值（注册即建 meter，缺 meter 即装配缺陷） */
    private double gaugeValue(String name) {
        return registry.get(name).gauge().value();
    }

    /** 读 counter 当前值（FunctionCounter 以 counter 视图读取） */
    private double counterValue(String name) {
        return registry.get(name).functionCounter().count();
    }

    /** 构造指定攒批容量的 IotProperties（测试资产假值，amqp 组 enabled=false 不触连接） */
    private static IotProperties propertiesWithCapacity(int capacity) {
        return new IotProperties(
                new IotProperties.Amqp(
                        false,
                        null,
                        null,
                        null,
                        null,
                        1000,
                        500,
                        Duration.ofSeconds(2),
                        capacity,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(30)),
                new IotProperties.Fallback(null));
    }
}

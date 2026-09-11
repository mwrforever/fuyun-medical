package com.fuyun.iot.internal;

import com.fuyun.iot.properties.IotProperties;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * AMQP 消费链指标绑定（B4.3，BRIEF-PR4-01 §1.3 尾段/§4 internal 行）："积压水位 + 断链时长"
 * 双指标的本地可测部分——三 gauge + 双 counter 全部以消费者/攒批器的既有 Atomic 载体绑定
 * （状态回写由消费链路自行完成，本类只读不写，零侵入消费者公开结构）。
 *
 * <p><b>指标词表</b>：{@link #GAUGE_CONNECTED} 连接状态（1=连接正常 0=断链）、
 * {@link #GAUGE_DISCONNECT_SECONDS} 当前断链持续秒数（连接正常=0，supervisor 重建成功即回零）、
 * {@link #GAUGE_QUEUE_FILL_RATIO} 攒批有界队列填充率（0~1，本地积压水位口径——prefetch 限流
 * 下队列打满即消费速率落后于上行速率的先行信号）；{@link #COUNTER_RECONNECT} supervisor 断链
 * 重建累计（IotAmqpReconnectIT 断链实测的"不雪崩"断言锚点）、{@link #COUNTER_FLUSH_FAILURE}
 * 攒批落库/确认失败累计。<b>真实积压水位（IoTDA 侧最旧未消费消息年龄）本地不可得，随 IOTDA
 * 联调补全</b>（简报 §10 附 8 延后登记项）。
 *
 * <p><b>gauge 弱引用契约</b>：Micrometer gauge 对状态对象持弱引用，本类以强引用字段持有消费者/
 * 攒批器/时钟（gauge lambda 捕获本类字段），注册后载体不因 GC 回收失效（值恒 NaN 防线）。
 *
 * <p>归 internal/ 包（观测基础设施非对外契约，宪法 B.1）；Bean 注册点为 IotAmqpConfig @Import
 * （随 AMQP 消费链 enabled 开关条件装配——开关关闭时无连接与攒批载体，指标无意义）；
 * JaCoCo 按 BUNDLE 0.80 承载。
 */
@Slf4j
public class IotAmqpMetrics {

    /** gauge：AMQP 连接状态（1=连接正常 0=断链） */
    public static final String GAUGE_CONNECTED = "iot.amqp.connected";

    /** gauge：当前断链持续秒数（连接正常=0） */
    public static final String GAUGE_DISCONNECT_SECONDS = "iot.amqp.disconnect.duration.seconds";

    /** gauge：攒批有界队列填充率（0~1，本地积压水位口径） */
    public static final String GAUGE_QUEUE_FILL_RATIO = "iot.amqp.batch.queue.fill.ratio";

    /** counter：supervisor 断链重建累计次数 */
    public static final String COUNTER_RECONNECT = "iot.amqp.reconnect.total";

    /** counter：攒批落库/确认失败累计次数 */
    public static final String COUNTER_FLUSH_FAILURE = "iot.amqp.batch.flush.failure.total";

    /** 强引用载体：AMQP 消费者（connected/disconnect 起点与重建计数来源，防 gauge 目标被 GC） */
    private final IotAmqpTelemetryConsumer consumer;

    /** 强引用载体：遥测攒批器（队列深度与失败计数来源） */
    private final TelemetryBatchAssembler assembler;

    /** 强引用载体：断链时长换算时钟（与消费者建链凭证时钟同源注入） */
    private final Clock clock;

    /** 攒批队列容量（填充率分母，构造期从 IotProperties 提取，配置不可变） */
    private final int queueCapacity;

    /**
     * 全参构造器：构造即完成全部 meter 注册（装配完成后指标即刻可读）。
     *
     * @param registry   Micrometer 注册表，非空；来源：Boot actuator 自动装配（fuyun-app）
     * @param consumer   AMQP 消费者，非空；载体经包内 accessor 读取（同 internal 包）
     * @param assembler  遥测攒批器，非空；载体经包内 accessor 读取
     * @param properties IoT 配置属性，非空；仅消费 amqp().batchQueueCapacity() 作填充率分母
     * @param clock      断链时长换算时钟，非空；来源：IotAmqpConfig iotAmqpClock Bean
     */
    public IotAmqpMetrics(
            MeterRegistry registry,
            IotAmqpTelemetryConsumer consumer,
            TelemetryBatchAssembler assembler,
            IotProperties properties,
            Clock clock) {
        this.consumer = consumer;
        this.assembler = assembler;
        this.clock = clock;
        this.queueCapacity = properties.amqp().batchQueueCapacity();
        Gauge.builder(GAUGE_CONNECTED, consumer.connectedFlag(), AtomicLong::get)
                .description("AMQP 连接状态（1=连接正常 0=断链）")
                .register(registry);
        Gauge.builder(GAUGE_DISCONNECT_SECONDS, consumer.disconnectSinceMillis(), this::disconnectSeconds)
                .description("当前断链持续秒数（连接正常=0）")
                .register(registry);
        Gauge.builder(GAUGE_QUEUE_FILL_RATIO, assembler, this::queueFillRatio)
                .description("攒批有界队列填充率（0~1，本地积压水位口径）")
                .register(registry);
        FunctionCounter.builder(COUNTER_RECONNECT, consumer.reconnectCount(), AtomicInteger::get)
                .description("supervisor 断链重建累计次数（新时间戳凭证重建，含退避节奏）")
                .register(registry);
        FunctionCounter.builder(COUNTER_FLUSH_FAILURE, assembler.flushFailureCount(), AtomicLong::get)
                .description("攒批落库/确认失败累计次数（失败帧由 IoTDA 重推兜底）")
                .register(registry);
        log.info(
                "AMQP 双指标已注册：{} / {} / {}（真实 IoTDA 积压水位随联调补全，本地为攒批队列填充率口径）",
                GAUGE_CONNECTED,
                GAUGE_DISCONNECT_SECONDS,
                GAUGE_QUEUE_FILL_RATIO);
    }

    /**
     * 断链持续秒数换算：断链起点为 0（连接正常）恒返 0；非 0 按时钟差换算（上限即当前读数，
     * supervisor 重建成功置起点回零后本 gauge 同步回零）。
     *
     * @param disconnectSinceMillis 断链起点毫秒载体，非空
     * @return 断链持续秒数（连接正常=0.0）
     */
    private double disconnectSeconds(AtomicLong disconnectSinceMillis) {
        long since = disconnectSinceMillis.get();
        return since == 0 ? 0.0 : (clock.millis() - since) / 1000.0;
    }

    /**
     * 攒批队列填充率：深度 / 容量（分母取构造期提取的配置容量，防御性下限 1 防零除——容量由
     * IotProperties 约束恒为正整数，此为 gauge 读路径兜底）。
     *
     * @param target 攒批器，非空（Gauge 弱引用状态对象，与强引用字段同实例）
     * @return 填充率（0~1）
     */
    private double queueFillRatio(TelemetryBatchAssembler target) {
        return (double) target.pendingQueueSize() / Math.max(1, queueCapacity);
    }
}

package com.fuyun.iot.internal;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.IConsumeErrorLogService;
import com.fuyun.iot.service.IDeviceStatusService;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.ExceptionListener;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;

/**
 * AMQP 遥测消费者：IoTDA AMQP 主链路的唯一消费执行点（BRIEF-PR4-01 §1.3 + 宪法 A.5-9/A.5-10 全条款）。
 *
 * <p><b>两套确认机制（锁定决策 6，红线 4）</b>：本类走 Qpid JMS {@code Session.CLIENT_ACKNOWLEDGE}
 * <b>客户端确认</b>——调用批末消息 {@code message.acknowledge()} 时对其会话内此前全部已消费消息
 * <b>统一确认</b>（会话级累计语义：遥测帧攒批落库成功后由 {@link TelemetryBatchAssembler} 回调批末
 * 确认、毒丸帧落 iot_consume_error_log 后确认抛弃、状态帧即时处理后即时确认）；RabbitMQ 侧
 * {@code @RabbitListener} 为容器 <b>AUTO 确认</b>——监听方法成功返回即由容器确认（宪法 A.5-5）。
 * 两套机制互不相干，iot AMQP 主链路一律用客户端确认。
 *
 * <p><b>生命周期（宪法 A.5-9/A.5-15/B.3-4）</b>：实现 SmartLifecycle（非 @PostConstruct 起线程），
 * start 按队列清单各起一个消费线程（命名有界线程池随上下文关闭）；stop 优雅停机——先停拉取
 * （interrupt receive 等待并关连接），在途批排空由攒批器（phase=0 后于本类停止）承接；
 * isAutoStartup=true。连接失败在后台线程重试，<b>不阻塞应用启动</b>（宪法 B.4-4，fail-fast 仅限
 * DB/Redis/RabbitMQ）。
 *
 * <p><b>断链重建 supervisor</b>：实现 JMS ExceptionListener。transport 级 failover 透明重连
 * <b>不会刷新凭证内嵌的 13 位毫秒时间戳</b>，断链超 5 分钟后 IoTDA 将以时间戳过期拒绝重连——
 * 因此连接异常时<b>销毁旧连接并以新时间戳凭证重建</b>连接与 consumer：消费线程捕获连接级异常或
 * ExceptionListener 全局标记后，先 close 旧连接、再按 initialReconnectDelay（默认 3s）起步、
 * 指数退避至 maxReconnectDelay（默认 30s）封顶的节奏重建，连接成功即复位退避节奏并继续消费。
 *
 * <p><b>消费处理流程（四路同构）</b>：receive → 载体解码（字节/文本）→ TelemetryFrameParser 解析 →
 * 遥测帧入 {@link TelemetryBatchAssembler} 有界队列（满则阻塞等待背压）；状态帧即时交
 * {@link IDeviceStatusService}，返回档案 wardId（非 null）时以之补全事件载荷（P0 状态帧契约
 * 不含 wardId，档案行同数据源回填，恢复 /topic/iot/device-status/{wardId} 生产数据源）再经
 * 状态事件回调（构造期注入 {@link IotEventPublisher}#publishDeviceStatus，ObjectProvider 可选
 * 解析）发布 iot.device.status-changed 至 fy.topic；解析失败帧落 iot_consume_error_log
 * （stage=PARSE）后确认抛弃（毒丸隔离，不阻塞队列）。
 *
 * <p><b>连接数预算（宪法 A.5-9）</b>：连接数 = 实例数 × 每实例连接数（=配置队列数），上限
 * ≤32（IoTDA 单凭证上限）；P0 单实例 × ≤4 队列 = ≤4 连接，扩容上界 8 实例 × 4 队列 = 32。
 * 凭证仅经 fuyun.iot.amqp.*（env IOTDA_AMQP_*）注入，日志与异常消息禁含凭证值。
 *
 * <p>归 internal/ 包：容器驱动入口不外引（宪法 B.1），装配归 IotAmqpConfig @Import；
 * JaCoCo 按 BUNDLE 0.80 承载（fake JMS 单测承底，1.00 核心包规则不误伤难测基础设施类）。
 */
@Slf4j
public class IotAmqpTelemetryConsumer implements SmartLifecycle, ExceptionListener {

    /**
     * receive 阻塞等待切片（毫秒）：超时轮询保证 stop 能及时退出拉取循环；
     * IoTDA 无消息时每秒一次空轮询开销可忽略。
     */
    static final long RECEIVE_TIMEOUT_MILLIS = 1000L;

    /** 停机等待消费线程退出的上限：receive 切片 + 退避睡眠均可中断，超时按异常停机告警处置 */
    private static final long STOP_AWAIT_SECONDS = 15L;

    /** AMQP 消费链配置（队列清单/凭证/prefetch/退避参数），来源：fuyun.iot.amqp.* 绑定 */
    private final IotProperties.Amqp amqp;

    /** JMS 连接工厂（Qpid JmsConnectionFactory Bean，与 Spring AMQP 完全隔离，宪法 A.5-9） */
    private final ConnectionFactory connectionFactory;

    /** 遥测攒批器：遥测帧唯一下游（有界背压队列 + 批末统一确认） */
    private final TelemetryBatchAssembler assembler;

    /** 消费错误日志服务：毒丸帧留痕写入口（stage=PARSE，落库失败内部自吞） */
    private final IConsumeErrorLogService errorLogService;

    /** 设备状态服务：状态帧即时处理执行点，返回档案 wardId（非 null）决定是否触发状态事件回调 */
    private final IDeviceStatusService deviceStatusService;

    /** 时钟抽象：建链凭证时间戳与断链起点计时（可注入假时钟供单测进动） */
    private final Clock clock;

    /** 退避睡眠抽象：生产用 Thread::sleep，单测注入记录型假 sleeper 避免真实睡眠 */
    private final Sleeper sleeper;

    /** 启停 CAS 闸门：start/stop 幂等，兼作消费线程循环的停机信号 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 消费线程池：按队列数固定大小（命名、有界、随上下文关闭，宪法 B.3-4） */
    private volatile ExecutorService workerExecutor;

    /** 在册消费线程（每队列一个）：ExceptionListener 全局断链标记与 stop 兜底关连接的遍历载体 */
    private final List<QueueWorker> workers = new CopyOnWriteArrayList<>();

    /**
     * 状态事件回调（构造期注入）：设备状态 apply 成功后经回调发布 iot.device.status-changed 至
     * fy.topic——回调入参为以档案 wardId 补全后的事件（构造于本消费线程），回调运行于本消费
     * 线程（非事务上下文），满足发布器"事务内禁发送"调用约束；上下文未装配发布器时为空实现
     * 防御（无消费源头，回调不会被触达）。
     */
    private final Consumer<DeviceStatusEvent> statusEventSink;

    /** connected gauge 载体：1=连接正常 / 0=断链（IotAmqpMetrics 以此绑定 gauge iot.amqp.connected） */
    private final AtomicLong connectedFlag = new AtomicLong(0);

    /** 断链起点毫秒载体：0=连接正常（IotAmqpMetrics 绑定 gauge iot.amqp.disconnect.duration.seconds） */
    private final AtomicLong disconnectSinceMillis = new AtomicLong(0);

    /** supervisor 断链重建累计计数（IotAmqpMetrics 绑定 counter iot.amqp.reconnect.total） */
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    /**
     * 退避睡眠抽象（包内函数式接口）：隔离 Thread.sleep 供单测记录退避节奏。
     */
    @FunctionalInterface
    interface Sleeper {

        /**
         * 睡眠指定毫秒（可中断：停机打断退避等待直接退出）。
         *
         * @param millis 睡眠时长（毫秒），非负
         * @throws InterruptedException 停机中断；调用方应恢复中断标记并终止重建循环
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Spring 装配构造器（@Autowired 消歧，装配归 IotAmqpConfig @Import，宪法 A.1-7 构造器注入）：
     * Thread::sleep 退避 + 容器时钟 Bean（iotAmqpClock，生产恒为系统 UTC；IT 可注入固定时钟使
     * IoTDA 时间戳口令可预置）+ 状态事件发布器可选解析（B4.3 扇出接线）。
     *
     * <p>发布器为何经 {@link ObjectProvider} 可选解析而非必填参数：IotEventPublisher 归
     * IotMessagingConfig 装配（MQ 事件总线域，无条件 Bean），本消费者归 IotAmqpConfig 装配
     * （AMQP 消费链域，enabled 开关条件 Bean）——两域解耦，消费链启用而事件域未装配时发布器
     * 缺席合法，回调降级为空实现；getIfAvailable 于构造期解析（发布器依赖链 RabbitTemplate/
     * codec 与本类无装配环，即时解析安全）。
     *
     * @param properties          IoT 配置属性，非空；本类仅消费其 amqp() 消费链参数
     * @param connectionFactory   Qpid JMS 连接工厂，非空；来源：IotAmqpConfig Bean
     * @param assembler           遥测攒批器，非空；来源：IotAmqpConfig @Import
     * @param errorLogService     消费错误日志服务，非空；来源：IotConfig 装配链
     * @param deviceStatusService 设备状态服务，非空；来源：IotConfig 装配链
     * @param clock               凭证时间戳时钟，非空；来源：IotAmqpConfig iotAmqpClock Bean
     * @param statusEventPublisher 状态事件发布器解析器，非空；来源：IotMessagingConfig 装配
     *                             （缺席时回调为空实现）
     */
    @Autowired
    public IotAmqpTelemetryConsumer(
            IotProperties properties,
            ConnectionFactory connectionFactory,
            TelemetryBatchAssembler assembler,
            IConsumeErrorLogService errorLogService,
            IDeviceStatusService deviceStatusService,
            Clock clock,
            ObjectProvider<IotEventPublisher> statusEventPublisher) {
        this(
                properties,
                connectionFactory,
                assembler,
                errorLogService,
                deviceStatusService,
                clock,
                Thread::sleep,
                optionalSink(statusEventPublisher));
    }

    /**
     * 全参构造器（包内测试用）：显式注入时钟、退避睡眠抽象与状态事件回调。
     *
     * @param clock               时钟，非空；凭证时间戳与断链计时来源
     * @param sleeper             退避睡眠，非空；可中断
     * @param statusEventSink     状态事件回调，非空；测试注入记录型收集器（生产为发布器方法引用）
     */
    IotAmqpTelemetryConsumer(
            IotProperties properties,
            ConnectionFactory connectionFactory,
            TelemetryBatchAssembler assembler,
            IConsumeErrorLogService errorLogService,
            IDeviceStatusService deviceStatusService,
            Clock clock,
            Sleeper sleeper,
            Consumer<DeviceStatusEvent> statusEventSink) {
        this.amqp = properties.amqp();
        this.connectionFactory = connectionFactory;
        this.assembler = assembler;
        this.errorLogService = errorLogService;
        this.deviceStatusService = deviceStatusService;
        this.clock = clock;
        this.sleeper = sleeper;
        this.statusEventSink = statusEventSink;
    }

    /**
     * 解析可选发布器为状态事件回调：发布器在场取其方法引用，缺席降级为空实现。
     *
     * @param statusEventPublisher 发布器解析器，非空
     * @return 状态事件回调，非空
     */
    private static Consumer<DeviceStatusEvent> optionalSink(ObjectProvider<IotEventPublisher> statusEventPublisher) {
        IotEventPublisher publisher = statusEventPublisher.getIfAvailable();
        return publisher == null ? event -> {} : publisher::publishDeviceStatus;
    }

    /** connected gauge 载体（单测断言 + IotAmqpMetrics gauge 绑定点），非空 */
    AtomicLong connectedFlag() {
        return connectedFlag;
    }

    /** 断链起点毫秒载体（0=正常；单测断言 + IotAmqpMetrics 断链时长 gauge 绑定点），非空 */
    AtomicLong disconnectSinceMillis() {
        return disconnectSinceMillis;
    }

    /** supervisor 重建累计计数载体（单测断言 + IotAmqpMetrics counter 绑定点），非空 */
    AtomicInteger reconnectCount() {
        return reconnectCount;
    }

    /**
     * 启动消费：按队列清单各起一个消费线程（后台异步建链，连接失败由 supervisor 重试，
     * 不阻塞应用启动——宪法 B.4-4）。
     */
    @Override
    public void start() {
        List<String> queues = amqp.queues();
        if (queues == null || queues.isEmpty()) {
            // enabled=true 下队列清单缺失应已被 IotProperties.validateAmqpEnabled 拦截，此为防御性兜底
            log.warn("fuyun.iot.amqp.queues 为空，AMQP 遥测消费者跳过启动");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger workerSeq = new AtomicInteger(0);
        workerExecutor = Executors.newFixedThreadPool(
                queues.size(), runnable -> new Thread(runnable, "iot-amqp-worker-" + workerSeq.incrementAndGet()));
        for (String queueAddress : queues) {
            QueueWorker worker = new QueueWorker(queueAddress);
            workers.add(worker);
            workerExecutor.submit(worker);
        }
        // 连接数预算公示（宪法 A.5-9）：实例数 × 每实例连接数（=队列数）≤ 32（IoTDA 单凭证上限）
        log.info(
                "AMQP 遥测消费者已启动：queues={}，queuePrefetch={}，连接预算=实例数×{}≤32", queues, amqp.queuePrefetch(), queues.size());
    }

    /**
     * 优雅停机（宪法 A.5-15 顺序）：先停拉取（中断 receive 等待与退避睡眠），消费线程自行关闭
     * 本线程连接；在途批排空由攒批器 stop（phase=0 后于本类停止）承接。幂等：重复调用零副作用。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("AMQP 遥测消费者停机开始：先停拉取，在途批排空由攒批器生命周期承接");
        ExecutorService executor = workerExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(STOP_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("AMQP 消费线程停机等待超时（{}s），移交兜底关连接", STOP_AWAIT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("AMQP 消费者停机等待被中断，移交兜底关连接");
            }
        }
        // 兜底关闭残余连接（正常路径消费线程退出时已自行关闭，此处覆盖超时未退出的线程）
        workers.forEach(QueueWorker::closeContextQuietly);
        log.info("AMQP 遥测消费者已停机：supervisor 重建累计={}", reconnectCount.get());
    }

    /** 停机顺序契约：phase=1 后于攒批器（phase=0）启动、先于其停止——先停拉取再排空在途批。 */
    @Override
    public int getPhase() {
        return 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 运行态标记：消费线程池存续期间为 true（start/stop CAS 同源）。 */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * JMS ExceptionListener 回调：连接级异常的全局断链标记入口。
     *
     * <p>JMS 异常回调无法定位具体连接（P0 各队列共享同一 IoTDA 端点），故全局标记断链并关闭全部
     * 在册连接——消费线程经 receive 异常进入 supervisor 重建路径（对已正常关闭的线程幂等无害）；
     * 重建统一携带新时间戳凭证（透明重连不刷新时间戳的 IoTDA 语义对策）。
     *
     * @param exception 连接级异常，非空；消息不含凭证值
     */
    @Override
    public void onException(JMSException exception) {
        if (!running.get()) {
            return;
        }
        log.warn("AMQP 连接异常回调（ExceptionListener）：全局标记断链并触发重建，原因={}", exception.getMessage());
        markDisconnected();
        workers.forEach(QueueWorker::closeContextQuietly);
    }

    /** 标记断链：connected 置 0，断链起点仅在首个失败时记录（多线程并发失败不覆盖起点）。 */
    private void markDisconnected() {
        connectedFlag.set(0);
        disconnectSinceMillis.compareAndSet(0, clock.millis());
    }

    /** 标记连接恢复：connected 置 1，断链起点清零（断链时长指标回零）。 */
    private void markConnected() {
        connectedFlag.set(1);
        disconnectSinceMillis.set(0);
    }

    /**
     * 单队列消费线程：建链 → receive 循环 → 连接级异常进入 supervisor 重建（销毁旧连接 + 指数退避
     * + 新时间戳凭证）。
     */
    private final class QueueWorker implements Runnable {

        /** 本线程负责的队列地址（值原样传给 JMS createQueue；IoTDA 为服务端队列名） */
        private final String queueAddress;

        /** 当前 JMS 上下文（连接 + 会话载体）：null=未连接/断链待重建 */
        private volatile JMSContext context;

        /** 当前消息消费者：null=未连接/断链待重建 */
        private volatile JMSConsumer consumer;

        /** 当前退避延迟（毫秒）：连接成功复位为 0，连续失败按初始值起步指数增长至上限封顶 */
        private long currentBackoffMillis;

        private QueueWorker(String queueAddress) {
            this.queueAddress = queueAddress;
        }

        @Override
        public void run() {
            log.info("AMQP 消费线程启动：queue={}", queueAddress);
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    ensureConnected();
                    Message message = consumer.receive(RECEIVE_TIMEOUT_MILLIS);
                    if (message != null) {
                        dispatchSafely(queueAddress, message);
                    }
                } catch (RuntimeException e) {
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        // 停机引发的关闭异常或退避等待被中断：直接退出不再重建
                        break;
                    }
                    handleConnectionFailure(e);
                }
            }
            closeContextQuietly();
            log.info("AMQP 消费线程退出：queue={}", queueAddress);
        }

        /**
         * 确保连接可用：未连接时以<b>新时间戳凭证</b>建链并创建消费者。
         *
         * <p>凭证语义（IoTDA）：username = accessKey 明文；password = accessSecret + 13 位毫秒时间戳，
         * <b>每次建链刷新</b>（服务端校验偏差超 5 分钟即拒绝建链，故断链重建必须换新凭证而非依赖
         * failover 透明重连）。
         */
        private void ensureConnected() {
            if (consumer != null) {
                return;
            }
            String password = amqp.accessSecret() + clock.millis();
            JMSContext newContext =
                    connectionFactory.createContext(amqp.accessKey(), password, JMSContext.CLIENT_ACKNOWLEDGE);
            try {
                newContext.setExceptionListener(IotAmqpTelemetryConsumer.this);
                Queue destination = newContext.createQueue(queueAddress);
                JMSConsumer newConsumer = newContext.createConsumer(destination);
                consumer = newConsumer;
                context = newContext;
            } catch (RuntimeException e) {
                closeQuietly(newContext);
                throw e;
            }
            // 连接成功：复位退避节奏并标记恢复（断链时长指标回零）
            currentBackoffMillis = 0;
            markConnected();
            log.info("AMQP 连接已建立（新时间戳凭证）：queue={}", queueAddress);
        }

        /**
         * supervisor 断链重建：销毁旧连接 → 按退避节奏等待 → 触发重建（重试计数递增）。
         *
         * <p>退避节奏：首次失败等 reconnectInitialDelay（默认 3s），连续失败指数翻倍至
         * reconnectMaxDelay（默认 30s）封顶；重建成功后节奏复位（ensureConnected）。
         *
         * @param cause 连接级异常；消息不含凭证值
         */
        private void handleConnectionFailure(RuntimeException cause) {
            closeContextQuietly();
            markDisconnected();
            long delay = nextBackoffDelay();
            log.warn("AMQP 连接异常，{}ms 后以新时间戳凭证重建连接：queue={}，原因={}", delay, queueAddress, cause.getMessage());
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                // 停机打断退避等待：恢复标记，外层循环经中断标记退出
                Thread.currentThread().interrupt();
                return;
            }
            reconnectCount.incrementAndGet();
        }

        /** 计算下一次退避延迟：0→初始值，否则翻倍封顶（宪法 A.5-9 的 3s→30s 参数化形态）。 */
        private long nextBackoffDelay() {
            long initial = amqp.reconnectInitialDelay().toMillis();
            long max = amqp.reconnectMaxDelay().toMillis();
            if (currentBackoffMillis == 0) {
                currentBackoffMillis = initial;
            } else {
                currentBackoffMillis = Math.min(currentBackoffMillis * 2, max);
            }
            return currentBackoffMillis;
        }

        /** 幂等关闭本线程的消费者与连接（置空引用在前，防并发重复关闭）。 */
        private void closeContextQuietly() {
            JMSConsumer currentConsumer = consumer;
            JMSContext currentContext = context;
            consumer = null;
            context = null;
            closeQuietly(currentConsumer);
            closeQuietly(currentContext);
        }
    }

    /**
     * 单帧分发：载体解码 → 形态解析 → 遥测帧入攒批 / 状态帧即时处理 / 毒丸留痕抛弃。
     *
     * <p>异常分域：<b>毒丸</b>（非 JSON/缺字段/载体不可读）→ 落 iot_consume_error_log 后确认抛弃；
     * <b>业务失败</b>（落库/状态更新 DB 异常）→ 不确认不中断（IoTDA 重推，唯一约束兜底幂等）；
     * <b>连接级失败</b>（JMS 运行时异常/中断）→ 上抛交 supervisor 重建路径。
     *
     * @param queueAddress 来源队列地址（毒丸留痕溯源），非空
     * @param message      待分发消息，非空
     */
    private void dispatchSafely(String queueAddress, Message message) {
        String rawText;
        try {
            rawText = decodeBody(message);
        } catch (JMSException e) {
            // 载体不可读（非字节/文本消息等）：按毒丸处置防重推死循环（留痕内容为空引用 + 原因摘要）
            poisonAndAcknowledge(queueAddress, message, "", "帧载体读取失败（要求字节/文本消息）：" + e.getMessage());
            return;
        }
        try {
            ParsedFrame frame;
            try {
                frame = TelemetryFrameParser.parse(rawText.getBytes(StandardCharsets.UTF_8));
            } catch (FrameParseException e) {
                // 毒丸隔离：落 PARSE 错误日志后确认抛弃，不阻塞队列（FU-M14-01）
                poisonAndAcknowledge(queueAddress, message, rawText, e.getMessage());
                return;
            }
            if (frame instanceof ParsedFrame.TelemetryFrame telemetryFrame) {
                // 遥测帧入攒批（有界队列满则阻塞等待背压）；批末统一确认由攒批器落库成功后回调
                assembler.put(telemetryFrame.message(), unifiedAcknowledgeAction(message));
            } else if (frame instanceof ParsedFrame.StatusFrame statusFrame) {
                handleStatusFrame(statusFrame.event(), message);
            } else {
                // sealed 双形态穷尽兜底（新增形态未接线即显性暴露，不可达防御）
                throw new IllegalStateException("未接线的帧解析形态：" + frame.getClass().getName());
            }
        } catch (InterruptedException e) {
            // 停机打断背压等待：恢复标记，帧不确认交 broker 重投
            Thread.currentThread().interrupt();
        } catch (JMSRuntimeException e) {
            // 连接级失败（确认失败等 JMS 运行时异常）：上抛交 supervisor 重建
            throw e;
        } catch (RuntimeException e) {
            // 业务处理失败（落库/状态更新 DB 异常等）：不确认不中断消费线程，IoTDA 重推兜底
            log.error("遥测帧处理失败（业务异常），本帧不确认待 IoTDA 重推：queue={}，原因={}", queueAddress, e.getMessage(), e);
        }
    }

    /**
     * 状态帧即时处理：apply 成功（返回档案 wardId）以该 wardId 补全事件载荷后触发状态事件回调；
     * 返回 null（设备不存在/竞态未命中/档案未编病区）不发布；随后即时确认（状态帧不入攒批）。
     *
     * @param event   状态帧解析产物（wardId 恒 null——P0 契约不含，由本方法以档案值补全），非空
     * @param message 来源 JMS 消息，非空
     */
    private void handleStatusFrame(DeviceStatusEvent event, Message message) {
        Long wardId = deviceStatusService.apply(event);
        if (wardId != null) {
            // 以设备档案 ward_id 补全事件载荷（P0 状态帧契约不含 wardId，同数据行回填）——
            // /topic/iot/device-status/{wardId} 生产数据源恢复（审核 F-2 死路径修复）
            statusEventSink.accept(new DeviceStatusEvent(event.deviceId(), event.status(), event.occurredAt(), wardId));
        }
        try {
            message.acknowledge();
        } catch (JMSException e) {
            // 确认失败属连接级异常：转运行时异常交 supervisor 重建（重投后状态更新幂等无害）
            throw new JMSRuntimeException(e.getMessage(), e.getErrorCode(), e);
        }
        log.info(
                "设备状态帧已处理：deviceId={}，status={}，wardId={}，occurredAt={}",
                event.deviceId(),
                event.status(),
                wardId,
                event.occurredAt());
    }

    /**
     * 毒丸留痕并确认抛弃：落 iot_consume_error_log（stage=PARSE）→ acknowledge → info 留痕。
     *
     * <p>留痕服务内部全吞落库失败（毒丸隔离优先于留痕），故本方法不因留痕失败中断；确认失败按
     * 连接级异常上抛（毒丸将随重推再次隔离，无死循环风险：留痕幂等仅多行）。
     *
     * @param queueAddress 来源队列地址，非空
     * @param message      毒丸消息，非空
     * @param rawText      帧原文（可空，空串兜底供摘要计算）
     * @param reason       失败原因摘要（不含原文敏感值），非空
     */
    private void poisonAndAcknowledge(String queueAddress, Message message, String rawText, String reason) {
        errorLogService.recordParseFailure(
                queueAddress, rawText == null ? "" : rawText, ConsumeErrorStage.PARSE.getCode(), reason);
        try {
            message.acknowledge();
        } catch (JMSException e) {
            throw new JMSRuntimeException(e.getMessage(), e.getErrorCode(), e);
        }
        log.info("毒丸帧已确认抛弃：queue={}，原因={}", queueAddress, reason);
    }

    /**
     * 解码消息载体为帧原文：优先 TextMessage 文本，其余按字节载体（IoTDA BytesMessage 主形态）。
     *
     * @param message 待解码消息，非空
     * @return UTF-8 帧原文，非空（空载体返回空串）
     * @throws JMSException 载体既非文本消息且字节读取失败（调用方按毒丸处置）
     */
    private static String decodeBody(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            String text = textMessage.getText();
            return text == null ? "" : text;
        }
        byte[] body = message.getBody(byte[].class);
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 构造消息的统一确认动作（JMS 受检异常适配为运行时异常，供攒批器以 Runnable 回调）。
     *
     * @param message 目标消息，非空；仅批末消息的该动作会被执行（会话级累计确认本批全部）
     * @return 确认动作，非空；确认失败抛 JMSRuntimeException（攒批器按失败计数处置，不确认待重推）
     */
    private static Runnable unifiedAcknowledgeAction(Message message) {
        return () -> {
            try {
                message.acknowledge();
            } catch (JMSException e) {
                throw new JMSRuntimeException(e.getMessage(), e.getErrorCode(), e);
            }
        };
    }

    /** 安静关闭消费者（停机路径幂等清理，关闭异常仅 debug 留痕）。 */
    private static void closeQuietly(JMSConsumer consumer) {
        if (consumer == null) {
            return;
        }
        try {
            consumer.close();
        } catch (RuntimeException e) {
            log.debug("JMS 消费者关闭异常（停机路径忽略）：{}", e.getMessage());
        }
    }

    /** 安静关闭 JMS 上下文（停机路径幂等清理，关闭异常仅 debug 留痕）。 */
    private static void closeQuietly(JMSContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (RuntimeException e) {
            log.debug("JMS 上下文关闭异常（停机路径忽略）：{}", e.getMessage());
        }
    }
}

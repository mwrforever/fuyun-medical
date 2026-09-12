package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.iot.internal.IotAmqpMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSContext;
import jakarta.jms.Queue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * AMQP 断链重连集成测试（PR-4 B4.4，BRIEF-PR4-01 §6.3，T-R3-3 本地实测载体）。
 *
 * <p>业务意图：以真实 broker 断链验证消费者 supervisor 的「断链感知 → 指数退避重建（新时间戳
 * 凭证）→ 恢复续费消费」全链与「积压水位 + 断链时长」双指标的真实语义。IoTDA 真实端点 10 分钟
 * 断链演示属延后条款（本地无 IOTDA_* 六变量，且本地 broker 无法复现「时间戳超 5 分钟拒绝建链」
 * 服务端语义——该语义的代码对策已由 supervisor 单测 + 本 IT 覆盖，延后登记 TASK.md）。
 *
 * <p>五步规格（§6.3 原文序，@Order 串联）：①锚点帧落库 + {@code iot.amqp.connected}=1；
 * ②{@code rabbitmqctl stop_app} 制造断链 → 轮询 connected=0 且
 * {@code iot.amqp.disconnect.duration.seconds} 随真实时间增长；③睡眠覆盖
 * initialReconnectDelay（3s）节奏窗口 → 断言 {@code iot.amqp.reconnect.total} 重建计数有限
 * （不雪崩）；④{@code start_app} 恢复 → 新锚点帧 → 轮询 connected 回 1 且新帧落库（supervisor
 * 新时间戳凭证重建成功、断链时长指标回零）；⑤稳定性护栏——轮询上限 90s 已为 CI 抖动放宽，
 * 若该类仍抖动允许再放宽上限一次并按修复循环处理，<b>禁止 @Disabled 或静默删除</b>（失效测试
 * 零容忍）。
 *
 * <p><b>可冻结时钟（本类特有）</b>：本地 broker 用户按「官方三段 username 字面 + accessSecret
 * 原值口令」预置，建链 username 必须恒等于该字面值——而断链时长指标又要求时钟真实前进。故注入
 * 可冻结的 Clock Bean（@Primary 覆盖 IotAmqpConfig.iotAmqpClock）：初始冻结于固定值（首连与
 * 断后重建的 username timestamp 段都用该字面值），步骤②释放为真实时钟（断链起点与增长进入
 * 真实时间域；断链期间的重建尝试因 broker 不可达在建链前即失败，错误凭证永不到达认证环节），
 * 步骤④重冻结回固定值（重建 username 与预置用户名重新对齐，重连成功）。
 *
 * <p><b>独立成类（§6.3）</b>：stop_app 会中断同容器的 Spring AMQP/扇出等其余链路，禁止与
 * IotTelemetryPipelineIT 共用容器或上下文，防互相污染。容器三件套与既有 IT 同款（tag 与
 * deploy compose 严格一致 + it/rabbitmq.conf 挂载 + @ServiceConnection），队列经管理 API 预声明
 * 为 quorum（AMQP 1.0 attach 不自动建队，IotTelemetryPipelineIT 实测口径同源）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotAmqpReconnectIT {

    /** TimescaleDB 容器：锚点帧落库断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（幂等构件等），本类不直接断言 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：AMQP 1.0 载体，步骤②④经 rabbitmqctl stop_app/start_app 制造与恢复断链 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /** 测试资产假 accessKey（与任何真实 IOTDA 凭证无关，仅用于容器内 rabbitmqctl 一次性建号） */
    private static final String TEST_ACCESS_KEY = "it-iot-reconnect-user";

    /** 测试资产假 accessSecret（与任何真实 IOTDA 凭证无关；凭证值不进任何断言与日志） */
    private static final String TEST_ACCESS_SECRET = "it-iot-reconnect-secret";

    /** 固定时钟毫秒值（13 位）：三段 username 的 timestamp 段字面值（测试资产） */
    private static final long FIXED_CLOCK_MILLIS = 1_700_000_000_000L;

    /** 固定时钟下的建链 username 字面值（官方三段格式，首连与断后重建均须命中，broker 用户按此预置） */
    private static final String TEST_AMQP_USERNAME =
            "accessKey=" + TEST_ACCESS_KEY + "|timestamp=" + FIXED_CLOCK_MILLIS + "|";

    /** 可冻结时钟（类级单例，经 @Primary Clock Bean 注入消费链；冻结/释放语义见类注释） */
    private static final FreezableClock IT_CLOCK = new FreezableClock(FIXED_CLOCK_MILLIS);

    /** 种子锚点设备号（消费链锚点，两步骤共用） */
    private static final String DEVICE_ID = "it-reconnect-001";

    /** 消费端订阅队列（fuyun.iot.amqp.queues[0]）：RabbitMQ 4.x address-v2 语法，独立命名防污染 */
    private static final String QUEUE_ADDRESS = "/queues/it.iot.reconnect";

    /** 队列声明的裸队列名（管理 API PUT 路径段） */
    private static final String QUEUE_BARE_NAME = "it.iot.reconnect";

    /** 步骤①锚点帧 metric（断链前上行的落库锚点） */
    private static final String ANCHOR_METRIC_BEFORE = "vital.reconnect-before";

    /** 步骤④锚点帧 metric（重连后上行的落库锚点，与断链前键区分防幂等误判） */
    private static final String ANCHOR_METRIC_AFTER = "vital.reconnect-after";

    /** 步骤③重连节奏观察窗口（秒）：传输层有限重试放弃（3 次 ≈9-12s，已在步骤②轮询内消耗）后
     * supervisor 以 3s 起步重建，本窗口覆盖重建计数落账（§6.3 断言窗口，简报原文 ≥3s 按实测放大） */
    private static final long BACKOFF_WINDOW_SLEEP_SECONDS = 6L;

    /** 断链时长指标增长观察间隔（毫秒）：两次读数间需可分辨的真实时间差 */
    private static final long DISCONNECT_GROWTH_SLEEP_MILLIS = 1200L;

    /** 全部轮询等待上限（90s）：已为 CI 抖动放宽一次的护栏值（§6.3 步骤⑤，再抖动走修复循环） */
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(90);

    /** DB/指标轮询步进：200ms 足够收敛 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 注入 AMQP 消费链测试参数与容器侧准备（与 IotTelemetryPipelineIT 同构）：假 HMAC 密钥 +
     * enabled=true + 端点指向本类容器 + 假凭证（测试资产）+ 订阅队列。容器侧两项准备——
     * rabbitmqctl 为假凭证建号授权（用户名 = 三段 username 字面、口令 = accessSecret 原值，
     * 与可冻结时钟初值对齐）、管理 API 预声明 quorum 订阅队列——均在 Spring 上下文启动前完成。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerReconnectProperties(DynamicPropertyRegistry registry) {
        try {
            RABBITMQ.execInContainer("rabbitmqctl", "add_user", TEST_AMQP_USERNAME, TEST_ACCESS_SECRET);
            RABBITMQ.execInContainer("rabbitmqctl", "set_user_tags", TEST_AMQP_USERNAME, "administrator");
            RABBITMQ.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", TEST_AMQP_USERNAME, ".*", ".*", ".*");
            declareItQuorumQueueViaManagementApi();
        } catch (Exception e) {
            throw new IllegalStateException("测试容器内 AMQP 假凭证建号或队列预建失败（测试资产，与真实凭证无关）", e);
        }
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
        registry.add("fuyun.iot.amqp.enabled", () -> "true");
        registry.add(
                "fuyun.iot.amqp.endpoint", () -> "amqp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(5672));
        registry.add("fuyun.iot.amqp.access-key", () -> TEST_ACCESS_KEY);
        registry.add("fuyun.iot.amqp.access-secret", () -> TEST_ACCESS_SECRET);
        registry.add("fuyun.iot.amqp.queues[0]", () -> QUEUE_ADDRESS);
    }

    /**
     * 经管理 HTTP API 预声明 IT 订阅队列（quorum 类型）：RabbitMQ 4.x AMQP 1.0 attach 不自动建队
     * （IotTelemetryPipelineIT 实测口径同源），HTTP 客户端强制 HTTP/1.1 防 h2c 升级断连。
     */
    private static void declareItQuorumQueueViaManagementApi() throws Exception {
        String auth = Base64.getEncoder()
                .encodeToString((TEST_AMQP_USERNAME + ":" + TEST_ACCESS_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(15672)
                                        + "/api/queues/%2F/" + QUEUE_BARE_NAME))
                                .header("Authorization", "Basic " + auth)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString("{\"durable\":true,\"auto_delete\":false,"
                                        + "\"arguments\":{\"x-queue-type\":\"quorum\"}}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 204) {
            throw new IllegalStateException("IT 订阅队列预声明失败：HTTP " + response.statusCode());
        }
    }

    /** JDBC 模板：锚点帧落库的业务断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** Micrometer 注册表：connected / disconnect.duration.seconds / reconnect.total 三指标读取口 */
    private final MeterRegistry meterRegistry;

    /**
     * 构造器注入（backend 宪法 A.1-7）：SpringExtension 从上下文解析依赖。
     *
     * @param jdbcTemplate JDBC 模板，非空
     * @param meterRegistry Micrometer 注册表，非空；IotAmqpMetrics 构造期已注册本类全部指标
     */
    @Autowired
    IotAmqpReconnectIT(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 步骤①：消费者后台建链 → 轮询 connected=1 → 发断链前锚点帧 → 断言落库（消费链基线就绪）。
     */
    @Test
    @Order(1)
    @DisplayName("基线：消费者建链 connected=1，断链前锚点帧真实落库")
    void baselineConnectionAndAnchorFrameLand() {
        awaitUntil("iot.amqp.connected 回 1（后台建链完成）", () -> connectedGauge() == 1.0d);
        sendFrameWithRetry(anchorJson(ANCHOR_METRIC_BEFORE));
        awaitUntil("断链前锚点帧落库", () -> countAnchorRows(ANCHOR_METRIC_BEFORE) == 1);
    }

    /**
     * 步骤②：释放时钟为真实时间域 → stop_app 制造断链 → 轮询 connected=0 且断链时长指标随
     * 真实时间增长（「断链时长」双指标之真实验证）。
     */
    @Test
    @Order(2)
    @DisplayName("断链：stop_app 后 connected=0 且 disconnect.duration.seconds 随真实时间增长")
    void brokerStopDrivesConnectedToZeroAndDisconnectDurationGrows() throws Exception {
        // 先释放时钟再断链：断链起点（markDisconnected）必须记入真实时间域，断链时长才会增长
        IT_CLOCK.release();
        RABBITMQ.execInContainer("rabbitmqctl", "stop_app");

        awaitUntil("iot.amqp.connected 归 0（断链被感知）", () -> connectedGauge() == 0.0d);
        awaitUntil("iot.amqp.disconnect.duration.seconds 开始增长", () -> disconnectSecondsGauge() > 0.0d);
        double atBegin = disconnectSecondsGauge();
        Thread.sleep(DISCONNECT_GROWTH_SLEEP_MILLIS);
        assertThat(disconnectSecondsGauge())
                .as("断链时长指标随真实时间增长（读数 %.3fs → 持续增大）", atBegin)
                .isGreaterThan(atBegin);
    }

    /**
     * 步骤③：睡眠覆盖 initialReconnectDelay（3s）节奏窗口 → 断言重建计数有限——supervisor 按
     * 3s 起步指数退避（3s→6s→12s…30s 封顶），观测窗内至多个位数次尝试，雪崩式重试即为缺陷。
     */
    @Test
    @Order(3)
    @DisplayName("退避不雪崩：覆盖 3s 起步窗口后 iot.amqp.reconnect.total 有限（≥1 且 ≤8）")
    void reconnectCounterGrowsWithinBackoffBoundsOnly() throws Exception {
        Thread.sleep(BACKOFF_WINDOW_SLEEP_SECONDS * 1000L);
        double attempts = reconnectCounter();
        assertThat(attempts).as("断链期间至少触发一次重建尝试（3s 窗口已覆盖）").isGreaterThanOrEqualTo(1.0d);
        assertThat(attempts).as("退避节奏下重建计数有限（指数退避生效，未雪崩）").isLessThanOrEqualTo(8.0d);
    }

    /**
     * 步骤④：start_app 恢复 broker → 重冻结时钟回固定值（重建 username 与预置用户名重新对齐）→ 轮询
     * connected 回 1 且断链时长指标回零 → 发新锚点帧 → 断言落库（supervisor 新时间戳凭证重建
     * 成功、消费续费）。
     */
    @Test
    @Order(4)
    @DisplayName("恢复：start_app 后 connected 回 1、断链时长回零，新锚点帧经重建连接落库")
    void brokerRestartRecoversConnectionAndConsumesNewAnchor() throws Exception {
        RABBITMQ.execInContainer("rabbitmqctl", "start_app");
        // 重冻结回固定值：此后 supervisor 重建凭证的 username timestamp 段回到字面预置值，与建号用户对齐；
        // 恢复前仍处于真实时钟域的失败尝试只会退避重试，无副作用
        IT_CLOCK.pin(FIXED_CLOCK_MILLIS);

        awaitUntil("iot.amqp.connected 回 1（supervisor 重建成功）", () -> connectedGauge() == 1.0d);
        assertThat(disconnectSecondsGauge()).as("重连成功后断链时长指标回零").isEqualTo(0.0d);

        sendFrameWithRetry(anchorJson(ANCHOR_METRIC_AFTER));
        awaitUntil("重连后新锚点帧落库（消费链路续费）", () -> countAnchorRows(ANCHOR_METRIC_AFTER) == 1);
    }

    /** connected gauge 读数（IotAmqpMetrics.GAUGE_CONNECTED）。 */
    private double connectedGauge() {
        return meterRegistry.get(IotAmqpMetrics.GAUGE_CONNECTED).gauge().value();
    }

    /** 断链持续秒数 gauge 读数（IotAmqpMetrics.GAUGE_DISCONNECT_SECONDS，连接正常=0）。 */
    private double disconnectSecondsGauge() {
        return meterRegistry
                .get(IotAmqpMetrics.GAUGE_DISCONNECT_SECONDS)
                .gauge()
                .value();
    }

    /** supervisor 重建累计 counter 读数（IotAmqpMetrics.COUNTER_RECONNECT）。 */
    private double reconnectCounter() {
        return meterRegistry
                .get(IotAmqpMetrics.COUNTER_RECONNECT)
                .functionCounter()
                .count();
    }

    /** 构造锚点帧 CF-7 JSON（occurredAt 固定值，metric 即唯一断言键）。 */
    private static String anchorJson(String metricCode) {
        return "{\"deviceId\":\"" + DEVICE_ID + "\",\"metricCode\":\"" + metricCode + "\",\"value\":\"42\","
                + "\"unit\":\"bpm\",\"occurredAt\":\"2026-09-10T08:00:00Z\"}";
    }

    /**
     * 以测试内 Qpid JMS 上下文投帧，断链/恢复窗口内 broker 可能短暂拒绝连接——按轮询上限重试
     * （帧内容固定，重试语义幂等：消费侧唯一约束去重兜底重复帧）。
     *
     * @param frame CF-7 JSON 帧，非空
     */
    private static void sendFrameWithRetry(String frame) {
        long deadline = System.currentTimeMillis() + PIPELINE_TIMEOUT.toMillis();
        IllegalStateException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                sendFrame(frame);
                return;
            } catch (IllegalStateException e) {
                lastFailure = e;
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("投帧重试等待被中断", ie);
                }
            }
        }
        throw new IllegalStateException("测试生产端投帧超时（broker 未就绪）", lastFailure);
    }

    /** 单次投帧（BytesMessage UTF-8 载荷，与消费端解码同源；凭证为字面预置的测试资产凭证）。 */
    private static void sendFrame(String frame) {
        JmsConnectionFactory producerFactory =
                new JmsConnectionFactory("amqp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(5672));
        try (JMSContext context = producerFactory.createContext(TEST_AMQP_USERNAME, TEST_ACCESS_SECRET)) {
            Queue queue = context.createQueue(QUEUE_ADDRESS);
            BytesMessage message = context.createBytesMessage();
            message.writeBytes(frame.getBytes(StandardCharsets.UTF_8));
            context.createProducer().send(queue, message);
        } catch (Exception e) {
            throw new IllegalStateException("测试生产端投帧失败（AMQP 1.0 直连容器）", e);
        }
    }

    /** 锚点帧落库行数（轮询探针：按 metric 唯一断言键计数）。 */
    private Integer countAnchorRows(String metricCode) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_telemetry WHERE device_id = ? AND metric_code = ?",
                Integer.class,
                DEVICE_ID,
                metricCode);
    }

    /** 轮询等待业务条件成立（异步消费链路确定性等待；超时后最终复核一次输出断言上下文）。 */
    private static void awaitUntil(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + PIPELINE_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /**
     * 可冻结时钟（IT 专用）：冻结时返回固定毫秒（建链凭证字面可预置），释放后回落真实 UTC 时间
     * （断链时长进入真实时间域）。仅本 IT 的容器 broker 场景需要，生产恒为系统时钟（IotAmqpConfig
     * 注释声明消费链路禁止替换，测试经 @Primary 覆盖为既有 IT 模式）。
     */
    static final class FreezableClock extends Clock {

        /** 冻结毫秒值载体：null=已释放（真实时间）；非 null=冻结值 */
        private volatile Long frozenMillis;

        /**
         * 构造即冻结于指定值（首连 username timestamp 段对齐 broker 预置字面值）。
         *
         * @param initialMillis 初始冻结毫秒值
         */
        FreezableClock(long initialMillis) {
            this.frozenMillis = initialMillis;
        }

        /** 冻结回指定毫秒值（恢复阶段重建 username 对齐预置用户名）。 */
        void pin(long millis) {
            this.frozenMillis = millis;
        }

        /** 释放为真实 UTC 时间（断链计时域）。 */
        void release() {
            this.frozenMillis = null;
        }

        /** 时区恒 UTC（与生产 iotAmqpClock 同源）。 */
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        /** 换区无意义（测试专用时钟），原样返回自身。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 当前时刻：冻结值或真实 UTC 时间。 */
        @Override
        public Instant instant() {
            Long frozen = frozenMillis;
            return frozen == null ? Instant.now() : Instant.ofEpochMilli(frozen);
        }

        /** 当前毫秒（消费链凭证与断链计时统一走本入口）。 */
        @Override
        public long millis() {
            Long frozen = frozenMillis;
            return frozen == null ? System.currentTimeMillis() : frozen;
        }
    }

    /**
     * IT 专用可冻结时钟 Bean：以 {@code @Primary} 覆盖 IotAmqpConfig 的 iotAmqpClock（IotTelemetryPipelineIT
     * 固定时钟同款注入模式，本类换为可冻结实现以同时满足「凭证字面预置」与「断链时长真实增长」）。
     */
    @TestConfiguration
    static class IotAmqpFreezableClockConfig {

        /**
         * 可冻结时钟 Bean（覆盖生产 iotAmqpClock）。
         *
         * @return 类级单例可冻结时钟，非空
         */
        @Bean
        @Primary
        Clock iotAmqpFreezableClock() {
            return IT_CLOCK;
        }
    }
}

package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.internal.IotAmqpMetrics;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
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
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * AMQP 落库失败重投集成测试（PR #7 审查 Finding 1 Critical 修复的端到端验证载体）。
 *
 * <p>业务意图：验证「落库失败 → 会话重建 → 未确认交付回归 broker 重投域 → 重投帧最终落库」全链
 * 真实成立——JMS CLIENT_ACKNOWLEDGE 为会话级累计确认，失败批若仅"零确认继续消费"会被后续成功批
 * 的累计确认静默吞掉（数据无痕丢失）；修复后落库失败即触发全局会话重建，会话销毁令失败帧回归
 * broker 重投域，重投帧由 iot_telemetry 唯一约束 ON CONFLICT DO NOTHING 幂等去重。该场景在单测
 * 中无法以 fake JMS 证明（broker 侧重投行为是真实 AMQP 语义），必须以真实 broker 断言。
 *
 * <p>失败注入手段：{@code @TestConfiguration} 以 {@code @Primary} 装饰器替换 ITelemetryIngestService
 * （首调抛异常模拟 DB 瞬断，其后委托真实 TelemetryIngestServiceImpl），攒批器 flush 首刷必失败 →
 * 触发会话重建 → broker 重投 → 第二次 ingest 命中委托真实落库。容器三件套与既有 IT 同款（tag 与
 * deploy compose 严格一致 + it/rabbitmq.conf 挂载），队列经管理 API 预声明为 quorum（AMQP 1.0
 * attach 不自动建队，既有 IT 实测口径同源）；固定时钟 Bean 使建链凭证字面可预置（重建凭证同值，
 * 既有 IT 同款注入模式）。
 *
 * <p>独立成类：装饰器替换全局落库 Bean 会改变 IotTelemetryPipelineIT 的链路行为（其七步断言依赖
 * 落库始终成功），禁止共用上下文，防互相污染（IotAmqpReconnectIT 独立成类先例）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IotAmqpIngestFailureRedeliveryIT {

    /** TimescaleDB 容器：重投帧最终落库断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（幂等构件等），本类不直接断言 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：AMQP 1.0 载体，承载「会话销毁 → 未确认交付回归重投域」的真实 broker 语义 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /** 测试资产假 accessKey（与任何真实 IOTDA 凭证无关，仅用于容器内 rabbitmqctl 一次性建号） */
    private static final String TEST_ACCESS_KEY = "it-iot-flushfail-user";

    /** 测试资产假 accessSecret（与任何真实 IOTDA 凭证无关；凭证值不进任何断言与日志） */
    private static final String TEST_ACCESS_SECRET = "it-iot-flushfail-secret";

    /** 固定时钟毫秒值（13 位）：三段 username 的 timestamp 段字面值（首连与重建同值，测试资产） */
    private static final long FIXED_CLOCK_MILLIS = 1_700_000_000_000L;

    /** 固定时钟下的建链 username 字面值（官方三段格式，首连与重建均须命中，broker 用户按此预置） */
    private static final String TEST_AMQP_USERNAME =
            "accessKey=" + TEST_ACCESS_KEY + "|timestamp=" + FIXED_CLOCK_MILLIS + "|";

    /** 首刷失败注入装饰器（类级单例，@Bean 装配时绑定真实委托，测试断言读其尝试计数） */
    private static final FailOnceIngestService FLAKY_INGEST = new FailOnceIngestService();

    /** 种子锚点设备号（重投帧唯一断言键的一半） */
    private static final String DEVICE_ID = "it-flushfail-001";

    /** 重投锚点帧 metric（唯一断言键的另一半，与既有 IT 键空间隔离防污染） */
    private static final String ANCHOR_METRIC = "vital.flushfail-redelivery";

    /** 消费端订阅队列（fuyun.iot.amqp.queues[0]）：RabbitMQ 4.x address-v2 语法，独立命名防污染 */
    private static final String QUEUE_ADDRESS = "/queues/it.iot.flushfail";

    /** 队列声明的裸队列名（管理 API PUT 路径段） */
    private static final String QUEUE_BARE_NAME = "it.iot.flushfail";

    /** 全部轮询等待上限（90s）：覆盖攒批 2s 时间窗 + supervisor 3s 起步退避 + broker 重投时延 */
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(90);

    /** DB/指标轮询步进：200ms 足够收敛 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 注入 AMQP 消费链测试参数与容器侧准备（既有 IT 同构）：假 HMAC 密钥 + enabled=true + 端点指向
     * 本类容器 + 假凭证（测试资产）+ 订阅队列。容器侧两项准备——rabbitmqctl 为假凭证建号授权
     * （用户名 = 三段 username 字面、口令 = accessSecret 原值，与固定时钟对齐）、管理 API 预声明
     * quorum 订阅队列——均在 Spring 上下文启动前完成。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFlushFailureProperties(DynamicPropertyRegistry registry) {
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
     * （既有 IT 实测口径同源），HTTP 客户端强制 HTTP/1.1 防 h2c 升级断连。
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

    /** JDBC 模板：重投帧最终落库的业务断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** Micrometer 注册表：connected、reconnect.total 与 batch.flush.failure.total 指标读取口 */
    private final MeterRegistry meterRegistry;

    /**
     * 构造器注入（backend 宪法 A.1-7）：SpringExtension 从上下文解析依赖。
     *
     * @param jdbcTemplate JDBC 模板，非空
     * @param meterRegistry Micrometer 注册表，非空；IotAmqpMetrics 构造期已注册本类全部指标
     */
    @Autowired
    IotAmqpIngestFailureRedeliveryIT(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 端到端主断言：落库首刷注入失败 → 攒批失败计数递增 + 会话重建 → broker 重投帧再次 ingest
     * （尝试 ≥2 次）→ 最终落库<b>恰 1 行</b>（重投幂等：与首投同唯一键，冲突忽略）。
     */
    @Test
    @DisplayName("落库失败重投全链：首刷失败触发会话重建，broker 重投帧最终落库且唯一约束幂等恰 1 行")
    void ingestFailureTriggersSessionRebuildAndBrokerRedeliveryEventuallyLands() {
        awaitUntil("iot.amqp.connected 回 1（后台建链完成）", () -> connectedGauge() == 1.0d);
        sendFrameWithRetry(anchorJson());

        // 断言 1：首刷失败真实发生（装饰器首调抛异常 + 攒批失败计数递增，经 Micrometer 载体读数）
        awaitUntil("首刷落库失败已发生（装饰器尝试计数 ≥1）", () -> FLAKY_INGEST.ingestAttempts() >= 1);
        awaitUntil("攒批失败计数递增（flush 失败路径真实走通）", () -> flushFailureCounter() >= 1.0d);

        // 断言 2：失败批经会话销毁回归 broker 重投域——重投帧再次 ingest 且最终落库（Critical 修复
        // 的端到端证据：修复前失败帧会被后续累计确认吞掉，永远不落库）
        awaitUntil("重投帧最终落库（iot_telemetry 出现锚点行）", () -> countAnchorRows() == 1);
        assertThat(FLAKY_INGEST.ingestAttempts())
                .as("落库尝试 ≥2 次（首刷失败 + broker 重投再刷，证明重投域语义成立）")
                .isGreaterThanOrEqualTo(2);

        // 断言 3：链路续费——会话重建成功后 connected 回 1（重建凭证与预置口令对齐，消费可继续）
        // 注：不断言 iot.amqp.reconnect.total——重建由外部关闭上下文触发，worker 视 receive 是否
        // 被打断而走 ensureConnected 直连或 supervisor 退避两条路径，计数是否递增属实现细节时序，
        // 业务结果断言（重投再刷 + 落库 + connected 恢复）已充分
        awaitUntil("iot.amqp.connected 回 1（会话重建成功、消费续费）", () -> connectedGauge() == 1.0d);

        // 断言 4：重投幂等——重投帧与首投帧同唯一键，唯一约束冲突忽略下恰 1 行（无重复落库）
        assertThat(countAnchorRows()).as("重投帧唯一约束幂等：恰 1 行").isEqualTo(1);
    }

    /** connected gauge 读数（IotAmqpMetrics.GAUGE_CONNECTED）。 */
    private double connectedGauge() {
        return meterRegistry.get(IotAmqpMetrics.GAUGE_CONNECTED).gauge().value();
    }

    /** 攒批落库失败累计 counter 读数（IotAmqpMetrics.COUNTER_FLUSH_FAILURE，flush 失败真实发生证据）。 */
    private double flushFailureCounter() {
        return meterRegistry
                .get(IotAmqpMetrics.COUNTER_FLUSH_FAILURE)
                .functionCounter()
                .count();
    }

    /** 构造锚点帧 CF-7 JSON（occurredAt 固定值，device+metric+occurredAt 构成唯一断言键）。 */
    private static String anchorJson() {
        return "{\"deviceId\":\"" + DEVICE_ID + "\",\"metricCode\":\"" + ANCHOR_METRIC + "\",\"value\":\"42\","
                + "\"unit\":\"bpm\",\"occurredAt\":\"2026-09-10T09:00:00Z\"}";
    }

    /**
     * 以测试内 Qpid JMS 上下文投帧，启动窗口内 broker 可能短暂拒绝连接——按轮询上限重试
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

    /** 锚点帧落库行数（轮询探针：按 device+metric 唯一断言键计数）。 */
    private Integer countAnchorRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_telemetry WHERE device_id = ? AND metric_code = ?",
                Integer.class,
                DEVICE_ID,
                ANCHOR_METRIC);
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
     * 首刷失败注入装饰器（IT 专用）：首次 ingest 抛异常模拟 DB 瞬断，其后委托真实
     * TelemetryIngestServiceImpl。仅攒批 flush 单线程调用，尝试计数原子即可；委托绑定发生在
     * @Bean 装配期（早于任何消费动作）。
     */
    static final class FailOnceIngestService implements ITelemetryIngestService {

        /** ingest 调用尝试计数（失败注入判据 + 测试断言读数载体） */
        private final AtomicInteger ingestAttempts = new AtomicInteger(0);

        /** 真实落库委托（@Bean 装配期绑定，非空于任何 ingest 调用之前） */
        private volatile ITelemetryIngestService delegate;

        /**
         * 绑定真实落库委托（@Bean 装配期调用一次）。
         *
         * @param delegate 真实落库服务实现，非空
         */
        void bind(ITelemetryIngestService delegate) {
            this.delegate = delegate;
        }

        /**
         * 首调抛异常（模拟 DB 瞬断），其后委托真实落库。
         *
         * @param batch 攒批整批消息，非空
         * @return 委托落库返回的插入行数
         */
        @Override
        public int ingest(List<StandardTelemetryMessage> batch) {
            if (ingestAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("IT 注入首刷落库失败（测试资产，模拟 DB 瞬断）");
            }
            return delegate.ingest(batch);
        }

        /** ingest 尝试次数读数（测试断言载体，非负）。 */
        int ingestAttempts() {
            return ingestAttempts.get();
        }
    }

    /**
     * IT 专用测试装配：固定时钟（建链与重建凭证字面可预置，既有 IT 同款注入模式）+ 首刷失败
     * 装饰器（@Primary 替换消费链的 ITelemetryIngestService，仅本类上下文生效）。
     */
    @TestConfiguration
    static class IotFlushFailureTestConfig {

        /**
         * 固定时钟 Bean（覆盖生产 iotAmqpClock）。
         *
         * @return 恒为 FIXED_CLOCK_MILLIS 的固定时钟，非空
         */
        @Bean
        @Primary
        Clock iotAmqpFixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(FIXED_CLOCK_MILLIS), ZoneOffset.UTC);
        }

        /**
         * 首刷失败装饰器 Bean（@Primary 替换真实落库服务，攒批器注入的即本装饰器）。
         *
         * @param real 真实落库服务实现，非空；来源：IotConfig 装配链
         * @return 首刷失败装饰器（类级单例），非空
         */
        @Bean
        @Primary
        ITelemetryIngestService flakyIngestService(TelemetryIngestServiceImpl real) {
            FLAKY_INGEST.bind(real);
            return FLAKY_INGEST;
        }
    }
}

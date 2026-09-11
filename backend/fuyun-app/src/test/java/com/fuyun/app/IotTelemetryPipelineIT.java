package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSContext;
import jakarta.jms.Queue;
import java.lang.reflect.Type;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * IoT 遥测管道端到端集成测试（PR-4 B4.2 骨架 + B4.3 任务 B 全量扩展，BRIEF-PR4-01 §6.2）。
 *
 * <p>业务意图：以真实三中间件 + 真实 Servlet 容器（RANDOM_PORT，STOMP 握手与 HTTP 兜底必需）
 * 打通「AMQP 注入 → Qpid 消费 → 四路解析 → 绑定快照 → 批量冲突忽略落库 → 客户端确认」、
 * 「落库 → STOMP 遥测摘要推送」、「状态帧 → 档案状态机 → 档案 wardId 补全自事件 → fy.topic →
 * 治理队列 AUTO+幂等消费 → STOMP 设备状态推送」与「HTTP 兜底通道独立鉴权 + 双通道同键去重」
 * 全链路。八步断言（§6.2 原文序 + PR-5 Finding 1 迁移后新增步骤 8）：①种子；②AMQP 注入落库
 * 幂等（4 帧两两重复键 → 恰 2 行 + 快照值）；③STOMP 摘要断言（订阅 /topic/iot/telemetry/1001
 * → 发 1 帧新遥测 → 收摘要帧含 deviceId/metricCode）；④毒丸隔离（非 JSON + 缺字段落 PARSE
 * 错误日志 PENDING → 锚点帧证明消费未阻塞）；⑤状态扇出 AMQP 全链（OFFLINE 帧→档案更新→
 * received_event PROCESSED；消费者以设备档案 ward_id=1001 补全自事件 → /topic/iot/device-status/1001
 * 收状态帧）；⑥幂等重投（台账真实已消费 eventId 重发跳过，台账行数不变）；⑦HTTP 兜底（无/错
 * token 401 IOT-1001；对 token 同键载荷 202 且 iot_telemetry 行数不增）；⑧STOMP 帧级鉴权负路径
 * （无/错令牌 CONNECT 被拒——ERROR 帧后连接关闭，会话无法建立）。
 *
 * <p><b>STOMP 客户端与帧级鉴权方案（PR-5 Finding 1 迁移后）</b>：spring-websocket
 * WebSocketStompClient(StandardWebSocketClient) 连 ws://localhost:{port}/ws/iot；令牌承载于
 * CONNECT 帧 Authorization 原生头（StompHeaders，与生产浏览器客户端 stompjs connectHeaders
 * 同通道——浏览器原生 WebSocket 无法携带自定义 HTTP 头，HTTP 升级头对生产客户端不可达），
 * 令牌为 IT 内经 HTTP 真实登录 M01 签发的 access 令牌——帧级鉴权走生产 TokenVerifier 全链，
 * 不为可测性削弱拦截器逻辑。
 *
 * <p><b>步骤⑤状态主题收帧为何即 AMQP 全链断言（审核 F-2 修复后）</b>：P0 状态帧契约不含
 * wardId（TelemetryFrameParser 解析恒 null，V403 占位载荷四字段），消费者以 IDeviceStatusService.apply
 * 返回的设备档案 ward_id 补全事件后再发布（it-dev-001 种子 ward_id=1001）——AMQP 状态帧经
 * 档案状态机 → fy.topic 自事件 → 幂等消费后自然携带 wardId 推达订阅主题，生产链路无死路径；
 * 步骤⑥手工信封仅作幂等重投样本（eventId 取自 integration.received_event 台账真实已消费行）。
 *
 * <p>容器三件套与 {@link IotMigrationIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）。RabbitMQ 4.x 原生 AMQP 1.0 默认启用（无插件），
 * Qpid JMS 直连 5672 映射口；生产/消费两侧统一使用 RabbitMQ address-v2 语法 /queues/{name}（4.x
 * 拒绝 address-v1 裸名），订阅队列经管理 API 预声明为 quorum（AMQP attach 不自动建队，实测
 * 2026-09-11，见 @DynamicPropertySource 注释）。消费者在建链失败/断链时由 supervisor 按退避节奏
 * 重建（测试轮询上限已覆盖 3s→30s 退避窗口）。
 *
 * <p>八步断言按 @Order 串联（消费状态跨步累积属业务链路语义）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotTelemetryPipelineIT {

    /** TimescaleDB 容器：iot_telemetry/iot_consume_error_log 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（幂等构件等），本类不直接断言 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：AMQP 1.0（4.x 核心协议默认启用）+ 消息治理三交换机载体 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /** 测试资产假 accessKey（与任何真实 IOTDA 凭证无关，仅用于容器内 rabbitmqctl 一次性建号） */
    private static final String TEST_ACCESS_KEY = "it-iot-amqp-user";

    /** 测试资产假 accessSecret（与任何真实 IOTDA 凭证无关；IoTDA 真实语义为 secret+时间戳拼出口令，
     * 容器 broker 只做等价性校验，凭证值本身不进任何断言与日志） */
    private static final String TEST_ACCESS_SECRET = "it-iot-amqp-secret";

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-iot-fallback-token";

    /** 固定时钟毫秒值（13 位）：IoTDA 口令内嵌时间戳需可预置，本地 broker 才能按字面口令建号 */
    private static final long FIXED_CLOCK_MILLIS = 1_700_000_000_000L;

    /** 固定时钟下的建链口令（= accessSecret + 13 位时间戳；broker 用户口令按此字面预置，测试资产） */
    private static final String TEST_COMPOSED_PASSWORD = TEST_ACCESS_SECRET + FIXED_CLOCK_MILLIS;

    /** 种子设备号：两步断言共用的消费链锚点 */
    private static final String DEVICE_ID = "it-dev-001";

    /** 步骤⑤状态帧发生时刻：OFFLINE 帧（last_offline_at 断言值来源） */
    private static final String STATUS_OCCURRED_AT = "2026-09-10T04:05:06Z";

    /** 步骤⑥锚点状态帧发生时刻：ONLINE 帧（与 OFFLINE 帧区分，驱动第二台账行） */
    private static final String ANCHOR_STATUS_OCCURRED_AT = "2026-09-10T05:06:07Z";

    /** 消费端订阅队列（fuyun.iot.amqp.queues[0]，值原样传给 JMS createQueue）：RabbitMQ 4.x 仅接受
     * address-v2 语法 /queues/{name}（address-v1 裸名被服务端拒绝 amqp_address_v1_not_permitted，
     * 2026-09-11 实测）；生产 IoTDA 侧配置为服务端裸队列名，消费者对配置值零加工直传 */
    private static final String QUEUE_ADDRESS = "/queues/it.iot.telemetry";

    /** 队列声明的裸队列名（管理 API PUT /api/queues/{vhost}/{name} 路径段） */
    private static final String QUEUE_BARE_NAME = "it.iot.telemetry";

    /** STOMP 握手与连接建立的等待上限（秒）：真实 Tomcat 升级握手 + CONNECT 应答富余取值 */
    private static final int STOMP_CONNECT_TIMEOUT_SECONDS = 30;

    /** 步骤①②③共用的 AMQP 链路等待上限：覆盖消费者退避重建（3s→30s 封顶）与攒批 2s 时间窗 */
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(90);

    /** DB 轮询步进：异步消费落库链路 200ms 步进足够收敛 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 绑定快照断言值：患者 ID（种子固定值） */
    private static final long SNAPSHOT_PATIENT_ID = 9001L;

    /** 绑定快照断言值：就诊 ID（种子固定值） */
    private static final long SNAPSHOT_VISIT_ID = 8001L;

    /** 绑定快照断言值：病区 ID（种子固定值，STOMP 两主题的路由锚点） */
    private static final long SNAPSHOT_WARD_ID = 1001L;

    /**
     * 注入 AMQP 消费链测试参数：HMAC 假密钥（既有 IT 同款）+ enabled=true + 端点指向容器 AMQP 1.0
     * 端口（5672 映射口）+ 假凭证（测试资产）+ 订阅队列 + 兜底共享密钥。执行前完成两项容器侧
     * 准备——①rabbitmqctl 为假凭证建号授权（RabbitMQ 对非 loopback 来源拒绝 guest，AMQP 1.0
     * 建链走统一认证，凭证值不进任何断言）；②管理 API 预声明 quorum 订阅队列（AMQP 1.0 attach
     * 不自动建队，且管理 API 需带 management 标签的用户）。以上均在 Spring 上下文启动前完成，
     * 消费者 attach 时队列已就位。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerIotPipelineProperties(DynamicPropertyRegistry registry) {
        try {
            RABBITMQ.execInContainer("rabbitmqctl", "add_user", TEST_ACCESS_KEY, TEST_COMPOSED_PASSWORD);
            // management 标签仅为管理 API 预建队列所需（AMQP 建链本身只需 set_permissions 的读写权限）
            RABBITMQ.execInContainer("rabbitmqctl", "set_user_tags", TEST_ACCESS_KEY, "administrator");
            RABBITMQ.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", TEST_ACCESS_KEY, ".*", ".*", ".*");
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
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /**
     * 经管理 HTTP API 预声明 IT 订阅队列（quorum 类型，与 it/rabbitmq.conf 默认队列类型同语义）。
     *
     * <p>为何不经 AMQP attach 自动建队：RabbitMQ 4.x AMQP 1.0 对 source/target attach 均不自动创建
     * 队列（实测返回 amqp:not-found / 投递被 released）；为何不用 Testcontainers withQueue：其对
     * 4.3 镜像派发的 rabbitmqadmin v2 命令行语法不兼容（unexpected argument，2026-09-11 实测）。
     * HTTP 客户端强制 HTTP/1.1（容器管理端点对 JDK HttpClient 的 h2c 升级握手直接断连）。
     */
    private static void declareItQuorumQueueViaManagementApi() throws Exception {
        String auth = Base64.getEncoder()
                .encodeToString((TEST_ACCESS_KEY + ":" + TEST_COMPOSED_PASSWORD).getBytes(StandardCharsets.UTF_8));
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

    /** JDBC 模板：遥测超表与消费错误日志的业务断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 设备档案 mapper：步骤①种子直插（mapper 直插定稿——顺带覆盖实体 @EnumValue 状态列映射） */
    private final IotDeviceMapper deviceMapper;

    /** 设备绑定 mapper：步骤①BOUND 绑定种子直插（快照五元组来源） */
    private final IotBindingMapper bindingMapper;

    /** 全局定制 JSON 转换器：幂等重投信封的载荷 JsonNode 构造（Long→String 定制与生产同源） */
    private final ObjectMapper objectMapper;

    /** RabbitTemplate：以治理装配的 JSON 转换器重发幂等重投帧（生产/测试同源，DictBroadcastIT 先例） */
    private final RabbitTemplate rabbitTemplate;

    /** 随机端口 HTTP 客户端：M01 真实登录取令牌（STOMP CONNECT 帧鉴权输入）与 HTTP 兜底端点调用 */
    private final TestRestTemplate restTemplate;

    /** 嵌入式 Servlet 容器随机端口：STOMP WebSocket 握手与 HTTP 兜底请求目标 */
    @LocalServerPort
    private int localServerPort;

    /**
     * 构造器注入（@Autowired 显式声明可注入构造器，backend 宪法 A.1-7）：SpringExtension 从上下文
     * 解析各依赖。
     *
     * @param jdbcTemplate JDBC 模板，非空
     * @param deviceMapper 设备档案 mapper，非空
     * @param bindingMapper 设备绑定 mapper，非空
     * @param objectMapper 全局定制 JSON 转换器，非空
     * @param rabbitTemplate MQ 发送模板，非空
     * @param restTemplate 随机端口 HTTP 客户端，非空（RANDOM_PORT 环境由 Boot 测试自动装配提供）
     */
    @Autowired
    IotTelemetryPipelineIT(
            JdbcTemplate jdbcTemplate,
            IotDeviceMapper deviceMapper,
            IotBindingMapper bindingMapper,
            ObjectMapper objectMapper,
            RabbitTemplate rabbitTemplate,
            TestRestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子 iot_device（ONLINE，ward=1001）与 iot_binding（BOUND，patient/visit 快照）。
     *
     * <p>经 mapper 直插（自选定稿）：审计列与 deleted 落数据库默认值，实体仅写业务列；插入成功即
     * 断言通过（后续步骤依赖种子存在，插入失败 fail-fast）。
     */
    @Test
    @Order(1)
    @DisplayName("种子：设备档案与 BOUND 绑定直插（绑定快照 9001/8001/1001 供消费链冗余）")
    void seedsDeviceAndBinding() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceName("IT 种子监护仪");
        device.setDeviceType("monitor");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(SNAPSHOT_WARD_ID);
        device.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(device)).as("设备档案种子插入成功").isEqualTo(1);

        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(DEVICE_ID);
        binding.setPatientId(SNAPSHOT_PATIENT_ID);
        binding.setVisitId(SNAPSHOT_VISIT_ID);
        binding.setBedId(2001L);
        binding.setWardId(SNAPSHOT_WARD_ID);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        assertThat(bindingMapper.insert(binding)).as("BOUND 绑定种子插入成功").isEqualTo(1);
    }

    /**
     * 步骤②：Qpid 生产端发 4 帧 CF-7 JSON（两两同 device+metric+occurredAt 重复键）→ 断言
     * iot_telemetry 恰 2 行且 patient_id/visit_id 为绑定快照值——消费 → 解析 → 快照冗余 → 批量
     * ON CONFLICT DO NOTHING → 客户端确认全链真实走通。
     */
    @Test
    @Order(2)
    @DisplayName("AMQP 注入落库幂等：4 帧两两重复键 → iot_telemetry 恰 2 行且含绑定快照值")
    void ingestsAmqpFramesWithConflictIgnoreAndBindingSnapshot() {
        // 同键两帧 value 不同（72/75、98/99）：唯一键冲突忽略，先到先行（幂等载体 = uk 三列唯一索引）
        String occurredAtHeartRate = "2026-09-10T01:02:03Z";
        String occurredAtSpo2 = "2026-09-10T02:03:04Z";
        List<String> frames = List.of(
                telemetryJson(DEVICE_ID, "vital.heart-rate", "72", occurredAtHeartRate),
                telemetryJson(DEVICE_ID, "vital.heart-rate", "75", occurredAtHeartRate),
                telemetryJson(DEVICE_ID, "vital.spo2", "98", occurredAtSpo2),
                telemetryJson(DEVICE_ID, "vital.spo2", "99", occurredAtSpo2));
        sendFrames(frames);

        awaitUntil("iot_telemetry 恰 2 行（4 帧两两重复键冲突忽略）", () -> countTelemetry() == 2);
        List<LineRow> rows = jdbcTemplate.query(
                "SELECT metric_code, value, patient_id, visit_id FROM iot.iot_telemetry"
                        + " WHERE device_id = ? ORDER BY metric_code",
                (rs, rowNum) -> new LineRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)),
                DEVICE_ID);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(LineRow::metricCode).containsExactly("vital.heart-rate", "vital.spo2");
        assertThat(rows).as("patient_id/visit_id 必须为绑定快照值（写入时冗余，14-iot §3.3）").allSatisfy(row -> {
            assertThat(row.patientId()).isEqualTo(SNAPSHOT_PATIENT_ID);
            assertThat(row.visitId()).isEqualTo(SNAPSHOT_VISIT_ID);
        });
    }

    /**
     * 步骤③：STOMP 摘要断言——先订阅 /topic/iot/telemetry/1001（等订阅收据回执确保生效），再发
     * 1 帧新遥测（新唯一键），断言收到摘要帧且含 deviceId/metricCode（落库成功后按病区推送，
     * BRIEF-PR4-01 §6.2 步骤 3）。
     */
    @Test
    @Order(3)
    @DisplayName("STOMP 摘要断言：订阅 /topic/iot/telemetry/1001 后发 1 帧新遥测，收到含 deviceId/metricCode 的摘要帧")
    void pushesTelemetrySummaryToStompTopicAfterBatchWrite() throws Exception {
        StompSession session = connectStompSession(loginAndGetAccessToken());
        try {
            BlockingQueue<String> frames = subscribeForFrames(session, "/topic/iot/telemetry/" + SNAPSHOT_WARD_ID);

            // 新唯一键帧（不与步骤②重复）：消费→落库→按绑定快照 ward 推摘要全链
            sendFrames(List.of(telemetryJson(DEVICE_ID, "vital.respiration", "18", "2026-09-10T02:30:00Z")));
            String frame = frames.poll(PIPELINE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).as("批落库后摘要帧到达订阅会话").isNotNull();
            JsonNode summary = objectMapper.readTree(frame);
            assertThat(summary.path("count").asInt()).as("摘要条数 = 本批 1 帧").isEqualTo(1);
            assertThat(summary.path("items").get(0).path("deviceId").asText()).isEqualTo(DEVICE_ID);
            assertThat(summary.path("items").get(0).path("metricCode").asText()).isEqualTo("vital.respiration");
        } finally {
            session.disconnect();
        }
    }

    /**
     * 步骤④：毒丸隔离——发 1 帧非 JSON + 1 帧缺必填字段 → 断言 iot_consume_error_log 两行 PENDING
     * （stage=PARSE、raw_digest 64 位）→ 发 1 帧合法锚点帧证明消费未阻塞且落库。
     */
    @Test
    @Order(4)
    @DisplayName("毒丸隔离：非 JSON 与缺字段帧落 PARSE 错误日志 PENDING，锚点帧证明消费未阻塞")
    void isolatesPoisonFramesThenConsumesAnchorFrame() {
        sendFrames(List.of("not-a-json-frame{{{", "{\"deviceId\":\"" + DEVICE_ID + "\"}"));

        awaitUntil("iot_consume_error_log 两行 PENDING stage=PARSE", () -> countConsumeErrors() == 2);
        List<ErrorRow> errors = jdbcTemplate.query(
                "SELECT error_stage, status, raw_digest FROM iot.iot_consume_error_log WHERE queue_name = ?",
                (rs, rowNum) -> new ErrorRow(rs.getString(1), rs.getString(2), rs.getString(3)),
                QUEUE_ADDRESS);
        assertThat(errors).hasSize(2);
        assertThat(errors)
                .as("毒丸留痕契约：stage=PARSE、status=PENDING、摘要 64 位（SHA-256 十六进制）")
                .allSatisfy(row -> {
                    assertThat(row.errorStage()).isEqualTo("PARSE");
                    assertThat(row.status()).isEqualTo("PENDING");
                    assertThat(row.rawDigest()).hasSize(64);
                });

        // 锚点帧：毒丸确认抛弃后消费循环未阻塞（新唯一键，正常解析落库）
        String anchorOccurredAt = "2026-09-10T03:04:05Z";
        sendFrames(List.of(telemetryJson(DEVICE_ID, "vital.temp", "36.8", anchorOccurredAt)));
        awaitUntil("锚点帧落库（毒丸未阻塞消费循环）", () -> countTelemetry() == 4);
        Integer anchorRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_telemetry WHERE device_id = ? AND metric_code = 'vital.temp'",
                Integer.class,
                DEVICE_ID);
        assertThat(anchorRows).as("锚点帧（vital.temp）恰好 1 行").isEqualTo(1);
    }

    /**
     * 步骤⑤：状态扇出 AMQP 全链——发 OFFLINE 状态帧 → 断言 iot_device.status=OFFLINE 且
     * last_offline_at 非空（状态帧即时处理不入攒批）；断言 integration.received_event 出现
     * consumer_module='iot' 且 status=PROCESSED 行（AMQP 状态帧→apply→消费者以档案 wardId 补全
     * 事件→IotEventPublisher 投 fy.topic→治理队列→AUTO+幂等消费全链）；随后轮询 STOMP 订阅
     * /topic/iot/device-status/1001 断言收到<b>AMQP 全链</b>状态帧（种子设备 ward_id=1001，
     * 不再以手工信封替代 AMQP 链路——推送在 recordProcessed 之前，收帧 + 落账共同证明全链完成）。
     */
    @Test
    @Order(5)
    @DisplayName("状态扇出全链：OFFLINE 帧→档案更新→档案 wardId 补全自事件→幂等消费落账→STOMP 设备状态主题收帧")
    void statusFrameFansOutThroughTopicAndSelfConsumptionRecordsLedger() throws Exception {
        StompSession session = connectStompSession(loginAndGetAccessToken());
        try {
            // 先订阅后触发：订阅收据回执保证服务端已登记订阅，推送帧不漏收
            BlockingQueue<String> frames = subscribeForFrames(session, "/topic/iot/device-status/" + SNAPSHOT_WARD_ID);

            sendFrames(List.of(statusJson(DEVICE_ID, "OFFLINE", STATUS_OCCURRED_AT)));
            // 断言 1：状态帧即时处理——档案状态与最近离线时刻落库
            awaitUntil(
                    "iot_device.status=OFFLINE 且 last_offline_at 非空", () -> countOfflineDeviceWithOfflineTime() == 1);
            // 断言 2：自事件全链——治理队列消费成功落 PROCESSED 台账（consumer_module=iot）
            awaitUntil("received_event 出现 consumer_module='iot' 的 PROCESSED 行", () -> iotProcessedLedgerRows() == 1);
            Map<String, Object> ledger = jdbcTemplate.queryForMap(
                    "SELECT event_type, producer, status FROM integration.received_event WHERE consumer_module = ?",
                    IotMessagingConstants.MODULE);
            assertThat(ledger.get("event_type"))
                    .as("自事件类型 = V403 登记事件")
                    .isEqualTo(IotMessagingConstants.EVENT_DEVICE_STATUS);
            assertThat(ledger.get("producer")).as("发布方 = iot（自事件往返）").isEqualTo(IotMessagingConstants.MODULE);
            assertThat(ledger.get("status"))
                    .as("台账状态 = PROCESSED")
                    .isEqualTo(MessagingConstants.RECEIVED_STATUS_PROCESSED);

            // 断言 3：AMQP 全链 STOMP 设备状态主题收帧——消费者以设备档案 ward_id（种子 1001）
            // 补全事件后经 fy.topic 往返推送，载荷四字段与契约同构
            String frame = frames.poll(PIPELINE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).as("设备状态主题收到 AMQP 全链状态帧").isNotNull();
            JsonNode status = objectMapper.readTree(frame);
            assertThat(status.path("deviceId").asText()).isEqualTo(DEVICE_ID);
            assertThat(status.path("status").asText()).isEqualTo("OFFLINE");
            assertThat(status.path("occurredAt").asText()).isEqualTo(STATUS_OCCURRED_AT);
            assertThat(status.path("wardId").asLong())
                    .as("载荷 wardId = 设备档案 ward_id（审核 F-2 补全语义）")
                    .isEqualTo(SNAPSHOT_WARD_ID);
        } finally {
            session.disconnect();
        }
    }

    /**
     * 步骤⑥：幂等重投（MQ 侧）——从 integration.received_event 台账读取步骤⑤<b>真实已消费</b>
     * eventId 重建同 eventId 信封手工重发 fy.topic → 标准范式 tryAcquire 前置键拦截 + D-7 回查
     * 确认已处理 → 跳过（含 STOMP 推送不重放）；断言该 (event_id, consumer_module) 台账行数仍为 1。
     *
     * <p>锚点机制（单队列单消费者按序处理）：重投帧发出后发第二帧真实 ONLINE 状态事件——锚点
     * 台账行可见即重投帧已被处理完毕，断言非轮询竞态假阳性。
     */
    @Test
    @Order(6)
    @DisplayName("幂等重投：台账真实已消费 eventId 同构信封重发 fy.topic 被跳过，台账行数仍为 1")
    void redeliveredStatusEventIsSkippedByIdempotencyLedger() {
        // 从台账读取真实已消费 eventId（uuid 列直取，重建信封模拟 at-least-once 服务端重投）
        UUID consumedEventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM integration.received_event WHERE consumer_module = ? AND status = ?",
                UUID.class,
                IotMessagingConstants.MODULE,
                MessagingConstants.RECEIVED_STATUS_PROCESSED);
        rabbitTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_EXCHANGE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                wardEnvelope(consumedEventId.toString(), "iot-fanout-it-redelivery"));

        // 锚点：第二帧真实状态事件（ONLINE）——锚点台账行可见即重投帧已被处理完毕
        sendFrames(List.of(statusJson(DEVICE_ID, "ONLINE", ANCHOR_STATUS_OCCURRED_AT)));
        awaitUntil("iot 幂等台账达 2 行（重投帧处理完毕 + 锚点事件落账）", () -> iotProcessedLedgerRows() == 2);

        // 幂等台账终局断言：重投 eventId 行数仍为 1（D-7：NX 失败 + 台账已处理 → 跳过）
        Integer redeliveredRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                consumedEventId,
                IotMessagingConstants.MODULE);
        assertThat(redeliveredRows).as("重投 eventId 台账行数仍为 1").isEqualTo(1);
    }

    /**
     * 步骤⑦：HTTP 兜底（B4.3）——无 token POST /ingest/iotda-fallback → 401 errorCode=IOT-1001；
     * 错 token → 401；对 token + 与步骤②同 (device,metric,occurredAt) 键的合法载荷 → 202 →
     * 断言 iot_telemetry 行数不增（兜底与 AMQP 同键唯一约束去重，14-iot §3.1 双通道去重口径；
     * 兜底入库为 HTTP 线程同步完成，202 返回即写入尝试已发生）。
     */
    @Test
    @Order(7)
    @DisplayName("HTTP 兜底：无/错 token 401 IOT-1001；对 token 同键载荷 202 且 iot_telemetry 行数不增")
    void httpFallbackAuthenticatesIndependentlyAndDeduplicatesSameKey() throws Exception {
        // 断言 1：无 token → 401 IOT-1001（ProblemDetail 全局渲染）
        ResponseEntity<String> missing = postJson("/ingest/iotda-fallback", fallbackBody(), Map.of());
        assertThat(missing.getStatusCode().value()).as("无 token 拒绝").isEqualTo(401);
        assertThat(objectMapper.readTree(missing.getBody()).path("errorCode").asText())
                .isEqualTo("IOT-1001");
        // 断言 2：错 token → 401 IOT-1001
        ResponseEntity<String> wrong = postJson(
                "/ingest/iotda-fallback", fallbackBody(), Map.of("X-Iot-Fallback-Token", "wrong-fallback-token"));
        assertThat(wrong.getStatusCode().value()).as("错 token 拒绝").isEqualTo(401);
        assertThat(objectMapper.readTree(wrong.getBody()).path("errorCode").asText())
                .isEqualTo("IOT-1001");

        // 断言 3：对 token + 与步骤②同唯一键载荷 → 202 受理，iot_telemetry 行数不增（双通道去重）
        int rowsBefore = countTelemetry();
        ResponseEntity<String> accepted =
                postJson("/ingest/iotda-fallback", fallbackBody(), Map.of("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN));
        assertThat(accepted.getStatusCode().value()).as("对 token 受理").isEqualTo(202);
        assertThat(objectMapper.readTree(accepted.getBody()).path("accepted").asBoolean())
                .isTrue();
        assertThat(countTelemetry()).as("同键载荷经唯一约束冲突忽略，iot_telemetry 行数不增").isEqualTo(rowsBefore);
    }

    /**
     * 步骤⑧：STOMP 帧级鉴权负路径（PR-5 Finding 1 迁移后新增）——无令牌/错令牌的 CONNECT 帧
     * 被服务端拒绝：帧级拦截器抛 MessagingException → 客户端收到 ERROR 帧（message=固定摘要）
     * → 服务端以 PROTOCOL_ERROR 关闭连接。spring 客户端在 CONNECTED 前连接关闭即连接未来异常
     * 完成（cause=ConnectionLostException）——断言会话无法建立，拒绝语义不弱化。
     */
    @Test
    @Order(8)
    @DisplayName("STOMP 帧级鉴权负路径：无/错令牌 CONNECT 被拒（ERROR 帧后连接关闭，会话无法建立）")
    void stompConnectWithMissingOrWrongTokenIsRejected() {
        // 无令牌：CONNECT 帧不带 Authorization 原生头
        assertThatThrownBy(() -> connectStompFuture(null).get(STOMP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("无令牌 CONNECT 必须被拒绝（连接无法建立）")
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ConnectionLostException.class);
        // 错令牌：Bearer 格式合法但 TokenVerifier 校验链不通过
        assertThatThrownBy(() -> connectStompFuture("Bearer it-invalid-token")
                        .get(STOMP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("错令牌 CONNECT 必须被拒绝（连接无法建立）")
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ConnectionLostException.class);
    }

    /** 构造带 wardId 的设备状态自事件信封（步骤⑥幂等重投样本，eventId 取自台账真实已消费行） */
    private EventEnvelope wardEnvelope(String eventId, String traceId) {
        return new EventEnvelope(
                eventId,
                Instant.now(),
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                MessagingConstants.ENVELOPE_DEFAULT_VERSION,
                traceId,
                objectMapper.valueToTree(new DeviceStatusEvent(
                        DEVICE_ID, DeviceStatus.OFFLINE, Instant.parse(STATUS_OCCURRED_AT), SNAPSHOT_WARD_ID)));
    }

    /** 经 M01 真实登录取 access 令牌（V303 种子 admin；令牌为 STOMP CONNECT 帧鉴权输入，不进断言与日志） */
    private String loginAndGetAccessToken() throws Exception {
        ResponseEntity<String> response = postJson(
                "/api/v1/system/auth/login", "{\"loginName\":\"admin\",\"password\":\"Fuyun@2026\"}", Map.of());
        assertThat(response.getStatusCode().value()).as("IT 登录成功").isEqualTo(200);
        String accessToken =
                objectMapper.readTree(response.getBody()).path("accessToken").asText();
        assertThat(accessToken).as("登录响应携带 access 令牌").isNotBlank();
        return accessToken;
    }

    /** JSON POST（Content-Type application/json，附加头按 map 合并），返回原始响应供断言 */
    private ResponseEntity<String> postJson(String uri, String jsonBody, Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        extraHeaders.forEach(headers::set);
        return restTemplate.exchange(uri, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /**
     * 建立 STOMP 会话：WebSocketStompClient(StandardWebSocketClient)，CONNECT 帧头携 Bearer 令牌
     * （帧级鉴权与生产浏览器客户端同通道，PR-5 Finding 1 迁移后口径）。
     *
     * @param accessToken access 令牌原文，非空；来源：{@link #loginAndGetAccessToken()}
     * @return 已完成 CONNECT 的会话，非空
     * @throws Exception 连接超时或被拒绝时抛出（正路径不应发生）
     */
    private StompSession connectStompSession(String accessToken) throws Exception {
        return connectStompFuture("Bearer " + accessToken).get(STOMP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 发起 STOMP 连接（正/负路径共用）：CONNECT 帧 Authorization 原生头携 Bearer 令牌；
     * authorization 为 null 时 CONNECT 帧不带该头（负路径无令牌样本）。令牌禁入 URL 与日志（红线 6）。
     *
     * @param authorization Authorization 头完整值（含 Bearer 前缀），null=不带该头
     * @return 未阻塞的连接未来（CONNECTED 完成即完成；被拒时异常完成，cause=ConnectionLostException）
     */
    private CompletableFuture<StompSession> connectStompFuture(String authorization) {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // 订阅收据回执（receipt）跟踪依赖客户端 TaskScheduler；短会话关闭心跳降低噪音
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("it-stomp-sched-");
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[] {0, 0});
        // 令牌承载于 CONNECT 帧原生头（帧级鉴权唯一输入）；HTTP 升级头保持为空（生产浏览器客户端不可达）
        StompHeaders connectHeaders = new StompHeaders();
        if (authorization != null) {
            connectHeaders.add(HttpHeaders.AUTHORIZATION, authorization);
        }
        return stompClient.connectAsync(
                "ws://localhost:" + localServerPort + "/ws/iot",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {});
    }

    /**
     * 订阅主题并返回帧队列。不使用 RECEIPT 回执确认订阅生效（STOMP 收据在本环境不稳定返回），
     * 依赖顺序与时序保证：SUBSCRIBE 与 CONNECT 复用同一条有序 WebSocket 连接（CONNECTED 已回执
     * 证明链路通），而推送源（AMQP 帧 → 攒批 2s 时间窗 → 落库 → 推送）滞后订阅写入至少 2s，
     * 订阅登记必然先于推送到达，帧不漏收。
     */
    private BlockingQueue<String> subscribeForFrames(StompSession session, String destination)
            throws InterruptedException {
        BlockingQueue<String> frames = new ArrayBlockingQueue<>(16);
        StompHeaders subscribeHeaders = new StompHeaders();
        subscribeHeaders.setDestination(destination);
        session.subscribe(subscribeHeaders, summaryFrameHandler(frames));
        Thread.sleep(500);
        return frames;
    }

    /** 帧处理器：STOMP 载荷按字节收取（服务端 Jackson JSON 串行化），UTF-8 解码入队供轮询断言 */
    private StompFrameHandler summaryFrameHandler(BlockingQueue<String> frames) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        };
    }

    /** 以测试内 Qpid JMS 上下文向生产地址投帧（BytesMessage UTF-8 载荷，与消费端解码同源）。 */
    private void sendFrames(List<String> frames) {
        JmsConnectionFactory producerFactory =
                new JmsConnectionFactory("amqp://" + RABBITMQ.getHost() + ":" + RABBITMQ.getMappedPort(5672));
        try (JMSContext context = producerFactory.createContext(TEST_ACCESS_KEY, TEST_COMPOSED_PASSWORD)) {
            Queue queue = context.createQueue(QUEUE_ADDRESS);
            for (String frame : frames) {
                BytesMessage message = context.createBytesMessage();
                message.writeBytes(frame.getBytes(StandardCharsets.UTF_8));
                context.createProducer().send(queue, message);
            }
        } catch (Exception e) {
            throw new IllegalStateException("测试生产端投帧失败（AMQP 1.0 直连容器）", e);
        }
    }

    /** 构造 CF-7 遥测帧 JSON（quality/source 走契约默认值，unit 携带 bpm）。 */
    private static String telemetryJson(String deviceId, String metricCode, String value, String occurredAt) {
        return "{\"deviceId\":\"" + deviceId + "\",\"metricCode\":\"" + metricCode + "\",\"value\":\"" + value
                + "\",\"unit\":\"bpm\",\"occurredAt\":\"" + occurredAt + "\"}";
    }

    /** 构造状态帧 JSON（P0 契约形态三字段：deviceId/status/occurredAt，14-iot §5）。 */
    private static String statusJson(String deviceId, String status, String occurredAt) {
        return "{\"deviceId\":\"" + deviceId + "\",\"status\":\"" + status + "\",\"occurredAt\":\"" + occurredAt
                + "\"}";
    }

    /** 构造 HTTP 兜底请求体：与步骤②首帧同唯一键（device+metric+occurredAt），value 不同（72/80）。 */
    private static String fallbackBody() {
        return "{\"deviceId\":\"" + DEVICE_ID + "\",\"metricCode\":\"vital.heart-rate\",\"value\":\"80\","
                + "\"occurredAt\":\"2026-09-10T01:02:03Z\"}";
    }

    /** iot_telemetry 当前设备行数（轮询探针）。 */
    private Integer countTelemetry() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_telemetry WHERE device_id = ?", Integer.class, DEVICE_ID);
    }

    /** iot_consume_error_log 当前队列错误行数（轮询探针）。 */
    private Integer countConsumeErrors() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_consume_error_log WHERE queue_name = ?", Integer.class, QUEUE_ADDRESS);
    }

    /** 步骤⑤探针：OFFLINE 状态已落档案且最近离线时刻非空的行数（单查询免多列类型映射）。 */
    private Integer countOfflineDeviceWithOfflineTime() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_device WHERE device_id = ? AND status = ?"
                        + " AND last_offline_at IS NOT NULL",
                Integer.class,
                DEVICE_ID,
                "OFFLINE");
    }

    /** iot 消费者幂等台账 PROCESSED 行数（轮询探针，P0 台账唯一写入值）。 */
    private Integer iotProcessedLedgerRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE consumer_module = ? AND status = ?",
                Integer.class,
                IotMessagingConstants.MODULE,
                MessagingConstants.RECEIVED_STATUS_PROCESSED);
    }

    /** 轮询等待业务条件成立（异步消费链路确定性等待；超时后最终复核一次以输出断言上下文）。 */
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

    /** 遥测行投影（metric/value + 绑定快照两列） */
    private record LineRow(String metricCode, String value, long patientId, long visitId) {}

    /** 错误日志行投影（stage/status/摘要） */
    private record ErrorRow(String errorStage, String status, String rawDigest) {}

    /**
     * IT 专用固定时钟：以 {@code @Primary} 覆盖 IotAmqpConfig 的 iotAmqpClock Bean——IoTDA 口令
     * 语义（accessSecret + 13 位时间戳）在固定时钟下可预置，本地 broker 才能按字面口令完成建号
     * 比对（真实 IoTDA 由服务端解析时间戳并校验 5 分钟偏差，无需固定）。
     */
    @TestConfiguration
    static class IotAmqpFixedClockConfig {

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
    }
}

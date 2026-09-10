package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

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
 * IoT 遥测管道端到端集成测试（PR-4 B4.2 骨架 + B4.3 步骤 5/6 扩展，BRIEF-PR4-01 §6.2）。
 *
 * <p>业务意图：以真实三中间件打通「AMQP 注入 → Qpid 消费 → 四路解析 → 绑定快照 → 批量冲突忽略
 * 落库 → 客户端确认」与「状态帧 → 档案状态机 → fy.topic 自事件 → 治理队列 AUTO+幂等消费」全链路
 * ——①种子设备档案与 BOUND 绑定（mapper 直插，快照值 9001/8001/1001）；②Qpid 生产端向
 * /queues/it.iot.telemetry 发 4 帧 CF-7 JSON（两两同 device+metric+occurredAt 重复键）→ 断言
 * iot_telemetry 恰 2 行且 patient_id/visit_id 为绑定快照值（唯一约束 ON CONFLICT DO NOTHING 幂等
 * 与快照冗余的真实链路证据）；③毒丸隔离：发 1 帧非 JSON + 1 帧缺必填字段 → 断言
 * iot_consume_error_log 两行 PENDING stage=PARSE（64 位摘要）→ 发 1 帧合法锚点帧证明消费未阻塞；
 * ④状态扇出全链：发 OFFLINE 状态帧 → 断言 iot_device.status=OFFLINE 且 last_offline_at 非空 +
 * integration.received_event 出现 consumer_module='iot' 的 PROCESSED 行（AMQP 状态帧→apply→发布器
 * 投 fy.topic→治理队列→AUTO+幂等消费）；⑤幂等重投：以已消费 eventId 同构信封手工重发 fy.topic →
 * 断言 (event_id, consumer_module) 台账行数仍为 1（标准范式 D-7 语义回归）。
 *
 * <p>容器三件套与 {@link IotMigrationIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）。RabbitMQ 4.x 原生 AMQP 1.0 默认启用（无插件），
 * Qpid JMS 直连 5672 映射口；生产/消费两侧统一使用 RabbitMQ address-v2 语法 /queues/{name}（4.x
 * 拒绝 address-v1 裸名），订阅队列经管理 API 预声明为 quorum（AMQP attach 不自动建队，实测
 * 2026-09-11，见 @DynamicPropertySource 注释）。消费者在建链失败/断链时由 supervisor 按退避节奏
 * 重建（测试轮询上限已覆盖 3s→30s 退避窗口）。
 *
 * <p>任务 B 扩展占位（B4.3 任务 B 范围）：步骤 3 = STOMP 遥测摘要断言（订阅
 * /topic/iot/telemetry/1001）；步骤 5 的 STOMP 设备状态主题断言（/topic/iot/device-status/1001）；
 * 步骤 7 = HTTP 兜底 401/202 与同键去重。
 *
 * <p>五步断言按 @Order 串联（消费状态跨步累积属业务链路语义）。
 */
@Testcontainers
@SpringBootTest
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

    /** 固定时钟毫秒值（13 位）：IoTDA 口令内嵌时间戳需可预置，本地 broker 才能按字面口令建号 */
    private static final long FIXED_CLOCK_MILLIS = 1_700_000_000_000L;

    /** 固定时钟下的建链口令（= accessSecret + 13 位时间戳；broker 用户口令按此字面预置，测试资产） */
    private static final String TEST_COMPOSED_PASSWORD = TEST_ACCESS_SECRET + FIXED_CLOCK_MILLIS;

    /** 种子设备号：两步断言共用的消费链锚点 */
    private static final String DEVICE_ID = "it-dev-001";

    /** 步骤④状态帧发生时刻：OFFLINE 帧（last_offline_at 断言值来源） */
    private static final String STATUS_OCCURRED_AT = "2026-09-10T04:05:06Z";

    /** 步骤⑤锚点状态帧发生时刻：ONLINE 帧（与 OFFLINE 帧区分，驱动第二台账行） */
    private static final String ANCHOR_STATUS_OCCURRED_AT = "2026-09-10T05:06:07Z";

    /** 消费端订阅队列（fuyun.iot.amqp.queues[0]，值原样传给 JMS createQueue）：RabbitMQ 4.x 仅接受
     * address-v2 语法 /queues/{name}（address-v1 裸名被服务端拒绝 amqp_address_v1_not_permitted，
     * 2026-09-11 实测）；生产 IoTDA 侧配置为服务端裸队列名，消费者对配置值零加工直传 */
    private static final String QUEUE_ADDRESS = "/queues/it.iot.telemetry";

    /** 队列声明的裸队列名（管理 API PUT /api/queues/{vhost}/{name} 路径段） */
    private static final String QUEUE_BARE_NAME = "it.iot.telemetry";

    /** 步骤①②③共用的 AMQP 链路等待上限：覆盖消费者退避重建（3s→30s 封顶）与攒批 2s 时间窗 */
    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(90);

    /** DB 轮询步进：异步消费落库链路 200ms 步进足够收敛 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 绑定快照断言值：患者 ID（种子固定值） */
    private static final long SNAPSHOT_PATIENT_ID = 9001L;

    /** 绑定快照断言值：就诊 ID（种子固定值） */
    private static final long SNAPSHOT_VISIT_ID = 8001L;

    /** 绑定快照断言值：病区 ID（种子固定值） */
    private static final long SNAPSHOT_WARD_ID = 1001L;

    /**
     * 注入 AMQP 消费链测试参数：HMAC 假密钥（既有 IT 同款）+ enabled=true + 端点指向容器 AMQP 1.0
     * 端口（5672 映射口）+ 假凭证（测试资产）+ 订阅队列。执行前完成两项容器侧准备——①rabbitmqctl
     * 为假凭证建号授权（RabbitMQ 对非 loopback 来源拒绝 guest，AMQP 1.0 建链走统一认证，凭证值
     * 不进任何断言）；②管理 API 预声明 quorum 订阅队列（AMQP 1.0 attach 不自动建队，且管理 API
     * 需带 management 标签的用户）。以上均在 Spring 上下文启动前完成，消费者 attach 时队列已就位。
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

    /**
     * 构造器注入（@Autowired 显式声明可注入构造器，backend 宪法 A.1-7）：SpringExtension 从上下文
     * 解析各依赖。
     *
     * @param jdbcTemplate JDBC 模板，非空
     * @param deviceMapper 设备档案 mapper，非空
     * @param bindingMapper 设备绑定 mapper，非空
     * @param objectMapper 全局定制 JSON 转换器，非空
     * @param rabbitTemplate MQ 发送模板，非空
     */
    @Autowired
    IotTelemetryPipelineIT(
            JdbcTemplate jdbcTemplate,
            IotDeviceMapper deviceMapper,
            IotBindingMapper bindingMapper,
            ObjectMapper objectMapper,
            RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
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
     * 步骤④：毒丸隔离——发 1 帧非 JSON + 1 帧缺必填字段 → 断言 iot_consume_error_log 两行 PENDING
     * （stage=PARSE、raw_digest 64 位）→ 发 1 帧合法锚点帧证明消费未阻塞且落库。
     */
    @Test
    @Order(3)
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
        awaitUntil("锚点帧落库（毒丸未阻塞消费循环）", () -> countTelemetry() == 3);
        Integer anchorRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_telemetry WHERE device_id = ? AND metric_code = 'vital.temp'",
                Integer.class,
                DEVICE_ID);
        assertThat(anchorRows).as("锚点帧（vital.temp）恰好 1 行").isEqualTo(1);
    }

    /**
     * 步骤⑤：状态扇出全链——发 OFFLINE 状态帧 → 断言 iot_device.status=OFFLINE 且
     * last_offline_at 非空（状态帧即时处理不入攒批）；断言 integration.received_event 出现
     * consumer_module='iot' 且 status=PROCESSED 行（AMQP 状态帧→apply→IotEventPublisher 投
     * fy.topic→治理队列→AUTO+幂等消费全链）。
     *
     * <p>STOMP 订阅 /topic/iot/device-status/1001 收帧断言随 B4.3 任务 B（推送服务接线）补充。
     */
    @Test
    @Order(4)
    @DisplayName("状态扇出全链：OFFLINE 状态帧→档案更新→fy.topic 自事件→治理队列幂等消费落 PROCESSED 台账")
    void statusFrameFansOutThroughTopicAndSelfConsumptionRecordsLedger() {
        sendFrames(List.of(statusJson(DEVICE_ID, "OFFLINE", STATUS_OCCURRED_AT)));

        // 断言 1：状态帧即时处理——档案状态与最近离线时刻落库
        awaitUntil("iot_device.status=OFFLINE 且 last_offline_at 非空", () -> countOfflineDeviceWithOfflineTime() == 1);
        // 断言 2：自事件全链——治理队列消费成功落 PROCESSED 台账（consumer_module=iot）
        awaitUntil("received_event 出现 consumer_module='iot' 的 PROCESSED 行", () -> iotProcessedLedgerRows() == 1);
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT event_type, producer, status FROM integration.received_event WHERE consumer_module = ?",
                IotMessagingConstants.MODULE);
        assertThat(ledger.get("event_type"))
                .as("自事件类型 = V403 登记事件")
                .isEqualTo(IotMessagingConstants.EVENT_DEVICE_STATUS);
        assertThat(ledger.get("producer")).as("发布方 = iot（自事件往返）").isEqualTo(IotMessagingConstants.MODULE);
        assertThat(ledger.get("status")).as("台账状态 = PROCESSED").isEqualTo(MessagingConstants.RECEIVED_STATUS_PROCESSED);
    }

    /**
     * 步骤⑥：幂等重投（MQ 侧）——以步骤⑤已消费 eventId 重建同构信封手工重发 fy.topic →
     * 标准范式 tryAcquire 前置键拦截 + D-7 回查确认已处理 → 跳过；断言该
     * (event_id, consumer_module) 台账行数仍为 1。
     *
     * <p>锚点机制（单队列单消费者按序处理）：重投帧发出后发第二帧真实 ONLINE 状态事件——锚点
     * 台账行可见即重投帧已被处理完毕，断言非轮询竞态假阳性。
     */
    @Test
    @Order(5)
    @DisplayName("幂等重投：已消费 eventId 同构信封重发 fy.topic 被跳过，台账行数仍为 1")
    void redeliveredStatusEventIsSkippedByIdempotencyLedger() {
        String firstEventId = jdbcTemplate
                .queryForObject(
                        "SELECT event_id FROM integration.received_event WHERE consumer_module = ?",
                        UUID.class,
                        IotMessagingConstants.MODULE)
                .toString();
        // 以已消费 eventId 重建同构信封（模拟 at-least-once 服务端重投，其余五要素与原信封同构）
        EventEnvelope redelivered = new EventEnvelope(
                firstEventId,
                Instant.now(),
                IotMessagingConstants.MODULE,
                IotMessagingConstants.EVENT_DEVICE_STATUS,
                MessagingConstants.ENVELOPE_DEFAULT_VERSION,
                "iot-fanout-it-redelivery",
                objectMapper.valueToTree(new DeviceStatusEvent(
                        DEVICE_ID, DeviceStatus.OFFLINE, Instant.parse(STATUS_OCCURRED_AT), null)));
        rabbitTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_EXCHANGE, IotMessagingConstants.EVENT_DEVICE_STATUS, redelivered);

        // 锚点：第二帧真实状态事件（ONLINE）——锚点台账行可见即重投帧已被处理完毕
        sendFrames(List.of(statusJson(DEVICE_ID, "ONLINE", ANCHOR_STATUS_OCCURRED_AT)));
        awaitUntil("iot 幂等台账达 2 行（重投帧处理完毕 + 锚点事件落账）", () -> iotProcessedLedgerRows() == 2);

        // 幂等台账终局断言：重投 eventId 行数仍为 1（D-7：NX 失败 + 台账已处理 → 跳过）
        Integer redeliveredRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                UUID.fromString(firstEventId),
                IotMessagingConstants.MODULE);
        assertThat(redeliveredRows).as("重投 eventId 台账行数仍为 1").isEqualTo(1);
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

    /** 步骤④探针：OFFLINE 状态已落档案且最近离线时刻非空的行数（单查询免多列类型映射）。 */
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

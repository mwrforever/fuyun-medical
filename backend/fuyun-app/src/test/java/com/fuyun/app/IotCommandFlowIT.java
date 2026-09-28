package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * IoT 命令五步下发验收锚点 IT（FU-M14-09，P2 PR-2 Task 18）：二次确认凭证签发 → 下发（在线同步
 * 回执）→ 白名单与凭证负路径 → command.completed 事件送达 → 凭证一次性重放拒绝。
 *
 * <p>五步断言按 @Order 串联：①种子（设备档案 + 白名单两行 + Redis 在线快照——预检以快照为唯一
 * 口径，DeviceStatusServiceImpl 写入侧同键同形态）；②白名单负路径（未登记命令 409 IOT-1014，
 * 下发门槛的最终裁决面在签发侧先行拦截）；③正路径全链（challenge 签发 → POST 下发 →
 * SimulatedRegistry 即时回执 SUCCESS 落终态 + q.it.iot.command.completed 真实收帧，载荷
 * commandNo/deviceId/status 锚定）；④无 challenge 下发 400 IOT-1015；⑤白名单 allowed=false
 * 行 409 IOT-1014（allowed 显式不放行与行缺失同码不同分支）；⑥凭证一次性重放拒绝（GETDEL
 * 消费后同 challengeId 二次下发 400 IOT-1015）。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（类级独占 + @ServiceConnection +
 * it/rabbitmq.conf 挂载）；登录/POST 助手复用 {@link FuyunStackITBase}。本类不启用 AMQP 遥测
 * 消费（fuyun.iot.amqp.enabled 缺省 false），仅依赖 fy.topic 事件扇出捕获链。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotCommandFlowIT extends FuyunStackITBase {

    /** TimescaleDB 容器：设备档案/命令白名单/命令日志断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：在线快照预检（步骤②）与凭证签发/GETDEL 消费（步骤③⑤）载体 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：fy.topic 事件扇出与治理捕获队列载体（本类独占 broker） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 命令目标设备号（快照 ONLINE，病区归属满足数据范围门槛） */
    private static final String DEVICE_ID = "it-cmd-001";

    /** 设备归属产品标识（白名单行的联合键前半） */
    private static final String PRODUCT_ID = "it-prod-001";

    /** 白名单放行命令（SAFETY 级，allowed=true） */
    private static final String ALLOWED_COMMAND = "it-reboot";

    /** 白名单显式不放行命令（SAFETY 级，allowed=false——行存在但不放行分支） */
    private static final String DENIED_COMMAND = "it-shutdown";

    /** 病区归属断言值（数据范围门槛前提） */
    private static final long WARD_ID = 1001L;

    /** 异步消费链路轮询等待上限 */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB/MQ 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 捕获队列声明（A.5-4 治理红线：经 MessagingGovernance 声明 "it" 消费者模块队列，声明副作用
     * 把 "it" 追加进 event_registry 订阅清单）。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_COMMAND_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_COMMAND_COMPLETED;

        static final List<EventEnvelope> COMPLETED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itCommandCompletedCaptureQueue(MessagingGovernance governance) {
            List<Declarable> declared = List.copyOf(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_COMMAND_COMPLETED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItCommandCaptureListener itCommandCaptureListener(EventEnvelopeCodec codec) {
            return new ItCommandCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（按事件类型收帧，不登记 received_event）。 */
    static class ItCommandCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCommandCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_COMMAND_COMPLETED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (IotMessagingConstants.EVENT_COMMAND_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.COMPLETED.add(envelope);
            }
        }
    }

    /** 设备档案 mapper：种子直插（IotTelemetryPipelineIT 同款定稿） */
    private final IotDeviceMapper deviceMapper;

    /** JDBC 模板：产品/白名单行播种与命令日志断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** String 模板：在线快照预检写入面（与 DeviceStatusServiceImpl 同键同形态） */
    private final StringRedisTemplate redisTemplate;

    /** 随机端口 HTTP 客户端：凭证签发与命令下发调用 */
    private final TestRestTemplate restTemplate;

    /** 命令号（步骤③下发成功后回填，事件载荷锚定与⑤重放断言锚） */
    private static String commandNo;

    /**
     * 构造器注入（backend 宪法 A.1-7）：SpringExtension 从上下文解析各依赖。
     *
     * @param deviceMapper  设备档案 mapper，非空
     * @param jdbcTemplate  JDBC 模板，非空
     * @param redisTemplate String 模板，非空
     * @param restTemplate  随机端口 HTTP 客户端，非空
     */
    @Autowired
    IotCommandFlowIT(
            IotDeviceMapper deviceMapper,
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            TestRestTemplate restTemplate) {
        this.deviceMapper = deviceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子设备档案（ONLINE，病区归属）+ 白名单两行（放行/显式不放行）+ Redis 在线快照
     * （预检唯一口径：快照缺失/未知态 IOT-1014 拒签拒发）。
     */
    @Test
    @Order(1)
    @DisplayName("种子：设备档案 + 白名单两行 + Redis 在线快照（预检唯一口径）")
    void seedsDeviceWhitelistAndOnlineSnapshot() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId(PRODUCT_ID);
        device.setDeviceName("IT 命令种子设备");
        device.setDeviceType("infusion-pump");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(device)).as("设备档案种子插入成功").isEqualTo(1);

        // 白名单两行（uk 逻辑唯一：product_id+command_name；allowed 显式两分支；id 为雪花段外
        // 夹具保留段直给——表无库端默认值，MP ASSIGN_ID 仅应用层生效，jdbcTemplate 直插须自带）
        jdbcTemplate.update(
                "INSERT INTO iot.iot_product_command (id, product_id, command_name, safety_level, allowed)"
                        + " VALUES (?, ?, ?, 'SAFETY', true), (?, ?, ?, 'SAFETY', false)",
                910001L,
                PRODUCT_ID,
                ALLOWED_COMMAND,
                910002L,
                PRODUCT_ID,
                DENIED_COMMAND);

        // 在线快照写入（生产为状态帧 apply 面，IT 直写同键同形态 JSON——下发预检以快照为唯一口径）
        String snapshot = "{\"deviceId\":\"" + DEVICE_ID + "\",\"status\":\"ONLINE\",\"lastOnlineAt\":null}";
        redisTemplate.opsForValue().set("fy:iot:snapshot:device-status:" + DEVICE_ID, snapshot, Duration.ofMinutes(10));
    }

    /**
     * 步骤②：白名单未登记负路径——签发侧门槛最终裁决：未登记命令 409 IOT-1014（白名单默认拒）。
     */
    @Test
    @Order(2)
    @DisplayName("白名单未放行（行缺失）：confirm-challenge 409 IOT-1014，凭证不签发")
    void challengeForUnregisteredCommandIsRejected() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        ResponseEntity<String> resp = postForEntity(
                token, "/api/v1/iot/commands/confirm-challenge", challengeBody(DEVICE_ID, "it-unknown-command"));
        assertThat(resp.getStatusCode().value())
                .as("未登记命令签发应 409，实况：%s", resp.getBody())
                .isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("IOT-1014");
    }

    /**
     * 步骤③：正路径全链——challenge 签发（预占命令号 CMD+日期+5 位流水）→ 下发同步等待回执：
     * SimulatedRegistry 即时 SUCCESS → 命令日志终态 SUCCESS + command.completed 帧真实送达
     * （载荷 commandNo/deviceId/commandName/status 锚定——步骤⑤事件发布单测敞口的本 IT 兜底）。
     */
    @Test
    @Order(3)
    @DisplayName("命令下发全链：challenge 签发 → 下发 SUCCESS（模拟回执即时）+ command.completed 帧送达")
    void challengeThenDispatchSucceedsWithCompletedEventDelivery() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        JsonNode challenge = postJson(
                "/api/v1/iot/commands/confirm-challenge", bearer(token), challengeBody(DEVICE_ID, ALLOWED_COMMAND));
        String challengeId = challenge.path("challengeId").asText();
        assertThat(challengeId).as("签发返回凭证 id").isNotBlank();
        assertThat(challenge.path("commandNo").asText())
                .as("预占命令号冻结形态 CMD+yyyyMMdd+5 位流水")
                .matches("CMD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "\\d{5}");

        ResponseEntity<String> resp =
                postForEntity(token, "/api/v1/iot/commands", dispatchBody(challengeId, DEVICE_ID, ALLOWED_COMMAND));
        assertThat(resp.getStatusCode().value())
                .as("下发应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        commandNo = vo.path("commandNo").asText();
        assertThat(vo.path("status").asText()).as("模拟注册中心即时回执 → 同步终态 SUCCESS").isEqualTo("SUCCESS");
        assertThat(vo.path("deliverMode").asText()).as("在线快照 ONLINE → 同步通道").isEqualTo("SYNC");

        Map<String, Object> row = awaitCommandRow(commandNo, "SUCCESS");
        assertThat(row.get("device_id")).isEqualTo(DEVICE_ID);
        assertThat(row.get("confirm_ref")).as("凭证引用留痕 = 签发 challengeId").isEqualTo(challengeId);

        awaitUntil("command.completed 帧送达", () -> !ItCaptureConfig.COMPLETED.isEmpty());
        EventEnvelope completed = ItCaptureConfig.COMPLETED.get(0);
        JsonNode payload = completed.payload();
        assertThat(payload.path("commandNo").asText()).as("事件载荷锚定同号").isEqualTo(commandNo);
        assertThat(payload.path("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(payload.path("commandName").asText()).isEqualTo(ALLOWED_COMMAND);
        assertThat(payload.path("status").asText()).as("终态载荷 SUCCESS").isEqualTo("SUCCESS");
    }

    /**
     * 步骤④：无 challenge 下发——凭证 GETDEL 缺失统一 IOT-1015（400），且命令日志不落行
     * （凭证消费先于门槛与落行，五步顺序不变量）。
     */
    @Test
    @Order(4)
    @DisplayName("无 challenge 下发：400 IOT-1015 且命令日志零新增")
    void dispatchWithoutValidChallengeIsRejected() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        Integer rowsBefore = countCommandRows();
        ResponseEntity<String> resp = postForEntity(
                token,
                "/api/v1/iot/commands",
                dispatchBody(java.util.UUID.randomUUID().toString(), DEVICE_ID, ALLOWED_COMMAND));
        assertThat(resp.getStatusCode().value())
                .as("无效凭证下发应 400，实况：%s", resp.getBody())
                .isEqualTo(400);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("IOT-1015");
        assertThat(countCommandRows()).as("凭证消费前置于落行，无凭证零落行").isEqualTo(rowsBefore);
    }

    /**
     * 步骤⑤：白名单显式不放行——allowed=false 行命中分支（行缺失分支见步骤②），409 IOT-1014。
     */
    @Test
    @Order(5)
    @DisplayName("白名单显式不放行（allowed=false 行）：confirm-challenge 409 IOT-1014")
    void challengeForDeniedWhitelistRowIsRejected() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        ResponseEntity<String> resp = postForEntity(
                token, "/api/v1/iot/commands/confirm-challenge", challengeBody(DEVICE_ID, DENIED_COMMAND));
        assertThat(resp.getStatusCode().value())
                .as("显式不放行命令应 409，实况：%s", resp.getBody())
                .isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("IOT-1014");
    }

    /**
     * 步骤⑥：凭证一次性重放拒绝——新签发凭证先正常下发（复用 SUCCESS 链），再以同 challengeId
     * 二次下发：GETDEL 已消费 → 400 IOT-1015（一次性语义，防重放）。
     */
    @Test
    @Order(6)
    @DisplayName("challenge 一次性重放拒：同 challengeId 二次下发 400 IOT-1015")
    void replayedChallengeIsRejectedByGetdelOneTimeSemantics() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        JsonNode challenge = postJson(
                "/api/v1/iot/commands/confirm-challenge", bearer(token), challengeBody(DEVICE_ID, ALLOWED_COMMAND));
        String challengeId = challenge.path("challengeId").asText();

        ResponseEntity<String> first =
                postForEntity(token, "/api/v1/iot/commands", dispatchBody(challengeId, DEVICE_ID, ALLOWED_COMMAND));
        assertThat(first.getStatusCode().value()).as("首发的凭证正常消费").isEqualTo(200);

        ResponseEntity<String> replay =
                postForEntity(token, "/api/v1/iot/commands", dispatchBody(challengeId, DEVICE_ID, ALLOWED_COMMAND));
        assertThat(replay.getStatusCode().value())
                .as("同凭证二次下发应 400，实况：%s", replay.getBody())
                .isEqualTo(400);
        assertThat(toNode(replay.getBody()).path("errorCode").asText()).isEqualTo("IOT-1015");
    }

    // ---------------------------------------------------------------- 种子与断言助手

    /** 签发请求体（deviceId+commandName+params；params 与下发体恒等——凭证一致性校验等值比对）。 */
    private ObjectNode challengeBody(String deviceId, String commandName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId).put("commandName", commandName);
        body.putObject("params").put("level", "low");
        return body;
    }

    /** 下发请求体（challengeId+deviceId+commandName+参数快照）。 */
    private ObjectNode dispatchBody(String challengeId, String deviceId, String commandName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("challengeId", challengeId).put("deviceId", deviceId).put("commandName", commandName);
        body.putObject("params").put("level", "low");
        return body;
    }

    /** Bearer 请求头（鉴权面走生产 TokenVerifier 全链）。 */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** JSON POST（返回原始响应供状态码与 errorCode 双断言）。 */
    private ResponseEntity<String> postForEntity(String token, String path, ObjectNode body) {
        return restTemplate.postForEntity(path, new HttpEntity<>(body, bearer(token)), String.class);
    }

    /** 无体响应解析兜底（NursingVitalSignFlowIT 同型收口）。 */
    private JsonNode toNode(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 命令日志行数（负路径零落行断言锚）。 */
    private int countCommandRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM iot.iot_command_log", Integer.class);
        return count == null ? 0 : count;
    }

    /** 等待命令行到达目标终态并整行返回（同步回执链路即时，轮询兜底调度抖动）。 */
    private Map<String, Object> awaitCommandRow(String no, String status) {
        awaitUntil("命令行落位（commandNo=" + no + "，status=" + status + "）", () -> !jdbcTemplate
                .queryForList("SELECT * FROM iot.iot_command_log WHERE command_no = ? AND status = ?", no, status)
                .isEmpty());
        return jdbcTemplate.queryForMap(
                "SELECT * FROM iot.iot_command_log WHERE command_no = ? AND status = ?", no, status);
    }

    /** 轮询等待业务条件成立（IotTelemetryPipelineIT 同款）。 */
    private static void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
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
}

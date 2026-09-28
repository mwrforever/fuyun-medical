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
import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * IoT 联动链验收锚点 IT（FU-M14-10，P2 PR-2 Task 18）：告警事件真实消费 → 条件匹配 → 联动留痕 →
 * executed 事件送达 → 条件不匹配零执行 → 人工重推幂等面。
 *
 * <p><b>触发链真栈形态</b>：HTTP 兜底通道注入越限遥测 → 引擎触发告警（i.alarm.triggered 发布）→
 * q.iot.iot.alarm.triggered 的 {@code IotAlarmEventListener}（AUTO 确认 + 幂等范式）真实消费 →
 * {@code LinkageExecutor} 条件匹配 → iot.iot_linkage_log 落行 + iot.linkage.executed 发布 →
 * 治理捕获队列 q.it.iot.linkage.executed 真实收帧。步骤②（条件不匹配零执行）与步骤③（人工重推
 * 幂等）为旁路负路径，不依赖消费时序。
 *
 * <p>三步断言按 @Order 串联：①种子（设备 + 阈值规则 + 联动规则两行——命中规则 M01_NOTIFY[GC17①
 * 降级留痕即 SUCCESS，确定性无 WS 依赖]、不命中规则 metric_code 异值）；②触发全链（两批越限 →
 * triggered 帧与 executed 帧双捕获，联动行 SUCCESS 且 trigger_ref=告警号、载荷
 * linkageNo/actionType/actionResult/triggerRef/triggerSource 锚定）；
 * ③条件不匹配零执行（异值 metric_code 规则零联动行）；④人工重推幂等（FAILED 夹具行重推一次
 * 迁移 SUCCESS 且 retry_count+1，再推 409 IOT-1018——CAS 旧值限定防重复执行的重放面）。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（类级独占 + @ServiceConnection +
 * it/rabbitmq.conf 挂载）；登录/POST 助手复用 {@link FuyunStackITBase}。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotLinkageFlowIT extends FuyunStackITBase {

    /** TimescaleDB 容器：告警规则/联动规则/联动日志断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：越限回合标记/发号器/风暴抑制载体 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：告警自事件消费链与治理捕获队列载体（本类独占 broker） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-iot-linkage-fallback-token";

    /** 阈值规则锚定设备号（告警触发链输入源） */
    private static final String DEVICE_ID = "it-lnk-001";

    /** 命中规则指标编码（词表外直通行，V1008 种子规则不串扰） */
    private static final String MATCH_METRIC = "vital.lnk-it";

    /** 不命中规则指标编码（与 MATCH_METRIC 异值——条件等值匹配的负样本） */
    private static final String MISMATCH_METRIC = "vital.other-metric";

    /** 病区路由断言值 */
    private static final long WARD_ID = 1001L;

    /** 命中联动规则 id（步骤①落行后回填，②断言锚） */
    private static long matchedRuleId;

    /** 不命中联动规则 id（步骤①落行后回填，③断言锚） */
    private static long mismatchedRuleId;

    /** 重推夹具规则 id（M01_NOTIFY 启用规则，④人工重推执行体） */
    private static long retryRuleId;

    /** 重推夹具联动行号（FAILED 初态，uk_iot_linkage_no 承载，④锚点） */
    private static final String RETRY_LINKAGE_NO = "LG-IT-RETRY-0001";

    /** 直插主键常量（MP ASSIGN_ID 为应用层雪花，jdbcTemplate 直插须自带 id；取雪花段外种子保留段） */
    private static final long RETRY_LOG_ID = 900021L;

    private static final long MATCHED_RULE_ID = 900022L;

    private static final long MISMATCHED_RULE_ID = 900023L;

    private static final long RETRY_RULE_ID = 900024L;

    /** 异步消费链路轮询等待上限 */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB/MQ 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 捕获队列声明：triggered（告警号锚定源）与 executed（联动结果断言面）各一队列（"it" 消费者
     * 模块形态，声明副作用登记订阅）。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_ALARM_TRIGGERED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_ALARM_TRIGGERED;

        static final String Q_LINKAGE_EXECUTED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_LINKAGE_EXECUTED;

        static final List<EventEnvelope> TRIGGERED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> EXECUTED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itLinkageCaptureQueues(MessagingGovernance governance) {
            List<Declarable> declared = new ArrayList<>();
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_ALARM_TRIGGERED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_LINKAGE_EXECUTED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItLinkageCaptureListener itLinkageCaptureListener(EventEnvelopeCodec codec) {
            return new ItLinkageCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（按事件类型分列表收帧，不登记 received_event）。 */
    static class ItLinkageCaptureListener {

        private final EventEnvelopeCodec codec;

        ItLinkageCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_ALARM_TRIGGERED, ItCaptureConfig.Q_LINKAGE_EXECUTED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (IotMessagingConstants.EVENT_ALARM_TRIGGERED.equals(envelope.eventType())) {
                ItCaptureConfig.TRIGGERED.add(envelope);
            } else if (IotMessagingConstants.EVENT_LINKAGE_EXECUTED.equals(envelope.eventType())) {
                ItCaptureConfig.EXECUTED.add(envelope);
            }
        }
    }

    /**
     * 注入兜底通道共享密钥（FuyunStackITBase 的密钥三元组对本类继续生效）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /** 设备档案 mapper：种子直插 */
    private final IotDeviceMapper deviceMapper;

    /** 告警规则 mapper：阈值规则种子直插 */
    private final IotAlarmRuleMapper alarmRuleMapper;

    /** JDBC 模板：联动规则/日志播种与断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：兜底通道与人工重推调用 */
    private final TestRestTemplate restTemplate;

    /**
     * 构造器注入（backend 宪法 A.1-7）：SpringExtension 从上下文解析各依赖。
     *
     * @param deviceMapper    设备档案 mapper，非空
     * @param alarmRuleMapper 告警规则 mapper，非空
     * @param jdbcTemplate    JDBC 模板，非空
     * @param restTemplate    随机端口 HTTP 客户端，非空
     */
    @Autowired
    IotLinkageFlowIT(
            IotDeviceMapper deviceMapper,
            IotAlarmRuleMapper alarmRuleMapper,
            JdbcTemplate jdbcTemplate,
            TestRestTemplate restTemplate) {
        this.deviceMapper = deviceMapper;
        this.alarmRuleMapper = alarmRuleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子设备 + 阈值规则 + 联动规则两行（M01_NOTIFY 命中/异值条件不命中）+ FAILED 夹具
     * 联动行（uk_iot_linkage_no 承载，重推面初态——NursingVitalSignFlowIT 批准口径的 jdbcTemplate
     * 夹具形态）。
     */
    @Test
    @Order(1)
    @DisplayName("种子：设备 + 阈值规则 + 联动规则两行 + FAILED 夹具联动行")
    void seedsDeviceRulesAndRetryFixture() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceName("IT 联动种子设备");
        device.setDeviceType("monitor");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(device)).as("设备种子插入成功").isEqualTo(1);

        IotAlarmRuleEntity rule = new IotAlarmRuleEntity();
        rule.setRuleName("IT 联动阈值规则");
        rule.setRuleType(AlarmRuleType.THRESHOLD);
        rule.setMetricCode(MATCH_METRIC);
        rule.setCompareOp(ThresholdOp.GT);
        rule.setThresholdValue(java.math.BigDecimal.valueOf(150));
        rule.setDurationSecs(1);
        rule.setRecoveryBand(java.math.BigDecimal.valueOf(10));
        rule.setAlarmLevel(AlarmLevel.WARNING);
        rule.setEnabled(true);
        assertThat(alarmRuleMapper.insert(rule)).as("阈值规则种子插入成功").isEqualTo(1);

        matchedRuleId = insertLinkageRule(MATCHED_RULE_ID, "IT 联动-命中", "{\"metric_code\":\"" + MATCH_METRIC + "\"}");
        mismatchedRuleId =
                insertLinkageRule(MISMATCHED_RULE_ID, "IT 联动-不命中", "{\"metric_code\":\"" + MISMATCH_METRIC + "\"}");
        retryRuleId = insertLinkageRule(RETRY_RULE_ID, "IT 联动-重推体", "{}");

        // FAILED 夹具行（重推面初态：执行体 M01_NOTIFY 确定性 SUCCESS；audit 列走库端默认值；
        // id 为 MP ASSIGN_ID 应用层雪花——jdbcTemplate 直插须自带主键）
        jdbcTemplate.update(
                "INSERT INTO iot.iot_linkage_log (id, linkage_no, rule_id, trigger_source, trigger_ref, action_type,"
                        + " action_result, retry_count, error_msg)"
                        + " VALUES (?, ?, ?, 'ALARM_TRIGGERED', 'AL-IT-RETRY-REF', 'M01_NOTIFY', 'FAILED', 1,"
                        + " 'IT 重推夹具初态')",
                RETRY_LOG_ID,
                RETRY_LINKAGE_NO,
                retryRuleId);
    }

    /**
     * 步骤②：触发全链——两批越限经兜底通道注入 → 告警触发（triggered 帧捕获）→ 消费链联动执行
     * （executed 帧捕获，载荷 linkageNo/actionType/actionResult/triggerRef 锚定；联动行 SUCCESS 且
     * trigger_ref = triggered 帧告警号——两帧勾稽即消费链真实闭环）。
     */
    @Test
    @Order(2)
    @DisplayName("告警事件触发联动：双帧捕获勾稽，联动行 SUCCESS 且 trigger_ref=告警号")
    void alarmEventDrivesLinkageExecutionWithCapturedFrames() throws Exception {
        postFallback(DEVICE_ID, MATCH_METRIC, "180", "2026-09-10T07:00:00Z");
        Thread.sleep(1_200L);
        postFallback(DEVICE_ID, MATCH_METRIC, "181", "2026-09-10T07:00:10Z");

        awaitUntil("triggered 帧送达", () -> !ItCaptureConfig.TRIGGERED.isEmpty());
        String alarmNo =
                ItCaptureConfig.TRIGGERED.get(0).payload().path("alarmNo").asText();
        assertThat(alarmNo).as("告警号冻结形态 AL+yyyyMMdd+5 位流水").matches("AL\\d{13}");

        awaitUntil("executed 帧送达", () -> !ItCaptureConfig.EXECUTED.isEmpty());
        EventEnvelope executed = ItCaptureConfig.EXECUTED.stream()
                .filter(env -> matchedRuleId == env.payload().path("ruleId").asLong())
                .findFirst()
                .orElseThrow(() -> new AssertionError("命中规则的 executed 帧未捕获"));
        JsonNode payload = executed.payload();
        assertThat(payload.path("actionResult").asText())
                .as("M01_NOTIFY 降级留痕即 SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(payload.path("actionType").asText())
                .as("动作类型快照 = M01_NOTIFY")
                .isEqualTo("M01_NOTIFY");
        assertThat(payload.path("linkageNo").asText()).as("联动号冻结形态 LG+13 位数字").matches("LG\\d{13}");
        assertThat(payload.path("triggerRef").asText()).as("载荷触发引用 = 告警号（跨帧勾稽）").isEqualTo(alarmNo);
        assertThat(payload.path("triggerSource").asText()).isEqualTo("ALARM_TRIGGERED");

        // 联动行库态终局（消费链落行）：结果/来源/引用三锚
        Map<String, Object> row = awaitLinkageRow(matchedRuleId, alarmNo, "SUCCESS");
        assertThat(row.get("trigger_source")).isEqualTo("ALARM_TRIGGERED");
        assertThat(((Number) row.get("retry_count")).intValue())
                .as("首试即成重试计数 0")
                .isZero();
    }

    /**
     * 步骤③：条件不匹配零执行——异值 metric_code 规则不参与执行（JSONB 键值等值匹配），该规则
     * 零联动行、零 executed 帧。
     */
    @Test
    @Order(3)
    @DisplayName("条件不匹配零执行：异值 metric_code 规则零联动行")
    void mismatchedConditionProducesNoExecution() {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_linkage_log WHERE rule_id = ?", Integer.class, mismatchedRuleId);
        assertThat(rows).as("不命中规则零联动行").isZero();
        assertThat(ItCaptureConfig.EXECUTED.stream()
                        .noneMatch(env ->
                                mismatchedRuleId == env.payload().path("ruleId").asLong()))
                .as("不命中规则零 executed 帧")
                .isTrue();
    }

    /**
     * 步骤④：人工重推幂等——FAILED 夹具行首次重推迁移 SUCCESS（retry_count 1→2，CAS 旧值限定），
     * 二次重推 409 IOT-1018（行已非 FAILED——幂等防重放面，与并发双推 CAS 零行同码）。
     */
    @Test
    @Order(4)
    @DisplayName("人工重推幂等：FAILED 行重推一次 SUCCESS 且 retry_count+1，再推 409 IOT-1018")
    void manualRetryIsCasIdempotent() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        ResponseEntity<String> first =
                postEmptyWithToken(token, "/api/v1/iot/linkage-logs/" + RETRY_LINKAGE_NO + "/retry");
        assertThat(first.getStatusCode().value())
                .as("FAILED 行重推应 200，实况：%s", first.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(first.getBody());
        assertThat(vo.path("actionResult").asText()).as("重推后终态 SUCCESS").isEqualTo("SUCCESS");
        assertThat(vo.path("retryCount").asInt()).as("累计重试计数 +1").isEqualTo(2);

        ResponseEntity<String> second =
                postEmptyWithToken(token, "/api/v1/iot/linkage-logs/" + RETRY_LINKAGE_NO + "/retry");
        assertThat(second.getStatusCode().value()).as("非 FAILED 行重推被拒").isEqualTo(409);
        assertThat(toNode(second.getBody()).path("errorCode").asText())
                .as("状态码位 IOT-1018")
                .isEqualTo("IOT-1018");
    }

    // ---------------------------------------------------------------- 种子与断言助手

    /**
     * 联动规则种子直插（条件原文直写 JSONB——触发条件词表校验面归服务层，IT 夹具直插为批准口径）。
     *
     * @param id            规则主键（MP ASSIGN_ID 为应用层雪花，jdbcTemplate 直插须自带；取雪花段外
     *                      种子保留段，避开 V1010 预置模板 900011/900012 号位）
     * @param ruleName      规则名称
     * @param conditionJson 触发条件 JSON 原文（JSONB 落列）
     * @return 落行后的规则 id（后续步骤断言锚）
     */
    private long insertLinkageRule(long id, String ruleName, String conditionJson) {
        // 触发条件/动作词表校验面归服务层（LinkageRuleServiceImpl），IT 夹具按批准口径直插库端
        jdbcTemplate.update(
                "INSERT INTO iot.linkage_rule (id, rule_name, trigger_source, trigger_condition, action_type, enabled)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                id,
                ruleName,
                "ALARM_TRIGGERED",
                conditionJson,
                "M01_NOTIFY",
                true);
        return id;
    }

    /** 兜底通道受理（202；与告警闭环 IT 同型）。 */
    private void postFallback(String deviceId, String metricCode, String value, String occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId)
                .put("metricCode", metricCode)
                .put("value", value)
                .put("occurredAt", occurredAt);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN);
        ResponseEntity<String> resp =
                restTemplate.postForEntity("/ingest/iotda-fallback", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value())
                .as("兜底受理应 202，实况：%s", resp.getBody())
                .isEqualTo(202);
    }

    /** 无体 POST（重推动作端点）。 */
    private ResponseEntity<String> postEmptyWithToken(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
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

    /** 等待规则+引用维度的联动行到达目标结果并整行返回（消费链异步，轮询等待）。 */
    private Map<String, Object> awaitLinkageRow(long ruleId, String triggerRef, String result) {
        awaitUntil("联动行落位（rule=" + ruleId + "，ref=" + triggerRef + "，result=" + result + "）", () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM iot.iot_linkage_log WHERE rule_id = ? AND trigger_ref = ? AND action_result = ?",
                        ruleId,
                        triggerRef,
                        result)
                .isEmpty());
        return jdbcTemplate.queryForMap(
                "SELECT * FROM iot.iot_linkage_log WHERE rule_id = ? AND trigger_ref = ? AND action_result = ?",
                ruleId,
                triggerRef,
                result);
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

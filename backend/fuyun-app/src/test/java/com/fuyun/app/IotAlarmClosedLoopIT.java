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
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.enums.ThresholdOp;
import com.fuyun.iot.mapper.IotAlarmRuleMapper;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.http.HttpMethod;
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
 * IoT 告警闭环验收锚点 IT（FU-M14-08，P2 PR-2 Task 18）：阈值触发→聚合→确认/关闭→模拟回放→
 * 离线告警与衍生抑制五段生命周期，全部真栈（真实三中间件 + 真实 Servlet 容器 + 真实 MQ 扇出）。
 *
 * <p><b>组合路径全链兜底（Task 7 单测敞口申报的 IT 兜底义务）</b>：单测无真实 DB/broker，
 * 「ingest afterCommit → AlarmEngine REQUIRES_NEW → iot.alarm.triggered 事件真实送达」的组合
 * 路径由本类步骤②实链验证——HTTP 兜底通道（/ingest/iotda-fallback，对 token 202 同步完成
 * ingest 事务提交与 afterCommit 评估）→ 引擎独立短事务落告警行并发布事件（AFTER_COMMIT 出
 * fy.topic）→ 治理声明捕获队列 q.it.iot.alarm.triggered 真实收帧，载荷五组件逐项锚定。
 *
 * <p>六步断言按 @Order 串联（触发/聚合/关闭状态跨步累积属业务链路语义）：①种子（设备×2 +
 * BOUND 绑定 + 阈值/离线规则，指标编码走词表外直通防 V1008 种子规则串扰）；②阈值触发全链
 * （两批越限 + 持续时长达标 → ACTIVE 行 + triggered 帧捕获）；③重复越限聚合（新回合触发命中
 * 活跃行 → trigger_count+1 不新行、无第二帧）；④确认/关闭 CAS（ACKNOWLEDGED→CLOSED）+
 * closed 帧捕获 + 终态重关 409；⑤模拟回放不落库（scannedRows 与触发重放，告警行数不变）；
 * ⑥离线告警与衍生抑制（OFFLINE 规则 DEVICE_OFFLINE 行 + 抑制③拦截遥测衍生新发）。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（tag 与 deploy compose 严格一致 +
 * it/rabbitmq.conf 挂载 + 类级独占 + @ServiceConnection）；登录/POST 助手复用
 * {@link FuyunStackITBase}（admin/Fuyun@2026）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotAlarmClosedLoopIT extends FuyunStackITBase {

    /** TimescaleDB 容器：iot_alarm/iot_telemetry 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：越限回合标记/发号器/风暴抑制载体 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：fy.topic 事件扇出与治理捕获队列载体（本类独占 broker） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-iot-alarm-loop-fallback-token";

    /** 阈值规则锚定设备号（绑定快照承载病区路由） */
    private static final String DEVICE_ID = "it-alm-001";

    /** 离线规则锚定设备号（步骤⑥抑制语义载体） */
    private static final String OFFLINE_DEVICE_ID = "it-alm-002";

    /** 阈值规则指标编码：词表外直通行（V1008 种子规则 MDC 编码不命中，防其 30s/60s 持续期回合串扰计数断言） */
    private static final String METRIC_CODE = "vital.heart-rate-it";

    /** 病区路由断言值（设备档案与绑定快照同 ward） */
    private static final long WARD_ID = 1001L;

    /** 绑定快照断言值：患者 ID（雪花段外固定值） */
    private static final long PATIENT_ID = 91001L;

    /** 绑定快照断言值：就诊号（CF-3 I 型 14 位） */
    private static final String VISIT_ID = "I2026090100001";

    /** 阈值规则持续时长秒：1 秒（两批间 Thread.sleep 1.2s 即达标，IT 耗时可控） */
    private static final int DURATION_SECS = 1;

    /** 两批越限帧间的等待毫秒：1.2s（覆盖 1s 持续时长 + 调度余量） */
    private static final long EPISODE_WAIT_MILLIS = 1_200L;

    /** 异步消费链路轮询等待上限：捕获队列收帧与 ward 消费链落行的确定性等待 */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB/MQ 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 捕获队列声明（A.5-4 治理红线：禁测试自声明交换机/裸队列——经 MessagingGovernance 声明，
     * NursingVitalSignFlowIT 的 "it" 消费者模块形态）。两事件一队列一闩一列表：triggered（步骤②
     * 唯一帧）与 closed（步骤④唯一帧）分事件锚定，声明副作用把 "it" 追加进订阅清单。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_ALARM_TRIGGERED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_ALARM_TRIGGERED;

        static final String Q_ALARM_CLOSED =
                MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_ALARM_CLOSED;

        static final List<EventEnvelope> TRIGGERED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> CLOSED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itAlarmCaptureQueues(MessagingGovernance governance) {
            List<org.springframework.amqp.core.Declarable> declared = new java.util.ArrayList<>();
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_ALARM_TRIGGERED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_ALARM_CLOSED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItAlarmCaptureListener itAlarmCaptureListener(EventEnvelopeCodec codec) {
            return new ItAlarmCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（测试侧轻量：按事件类型分列表收帧，不登记 received_event）。 */
    static class ItAlarmCaptureListener {

        private final EventEnvelopeCodec codec;

        ItAlarmCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_ALARM_TRIGGERED, ItCaptureConfig.Q_ALARM_CLOSED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (IotMessagingConstants.EVENT_ALARM_TRIGGERED.equals(envelope.eventType())) {
                ItCaptureConfig.TRIGGERED.add(envelope);
            } else if (IotMessagingConstants.EVENT_ALARM_CLOSED.equals(envelope.eventType())) {
                ItCaptureConfig.CLOSED.add(envelope);
            }
        }
    }

    /**
     * 注入兜底通道共享密钥（ingest 走 HTTP 兜底通道的真实鉴权面；FuyunStackITBase 的密钥三元组
     * 对本类继续生效——@DynamicPropertySource 基类/子类方法叠加处理）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
    }

    /** 设备档案 mapper：步骤①种子直插（IotTelemetryPipelineIT 同款定稿） */
    private final IotDeviceMapper deviceMapper;

    /** 设备绑定 mapper：步骤①BOUND 绑定种子直插（告警绑定快照来源） */
    private final IotBindingMapper bindingMapper;

    /** 告警规则 mapper：阈值/离线规则种子直插（规则 id 后续步骤断言锚） */
    private final IotAlarmRuleMapper alarmRuleMapper;

    /** JDBC 模板：告警行断言与离线设备 last_online_at 回填通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：兜底通道 202 与告警确认/关闭/模拟调用 */
    private final TestRestTemplate restTemplate;

    /** 阈值规则 id（步骤①落行后回填，跨步共享） */
    private static long thresholdRuleId;

    /** 离线规则 id（步骤①落行后回填，步骤⑥断言锚） */
    private static long offlineRuleId;

    /** 阈值告警业务号（步骤②捕获帧取得，步骤③④锚点） */
    private static String alarmNo;

    /**
     * 构造器注入（@Autowired 显式声明可注入构造器，backend 宪法 A.1-7）：SpringExtension 从上下文
     * 解析各依赖。
     *
     * @param deviceMapper    设备档案 mapper，非空
     * @param bindingMapper   设备绑定 mapper，非空
     * @param alarmRuleMapper 告警规则 mapper，非空
     * @param jdbcTemplate    JDBC 模板，非空
     * @param restTemplate    随机端口 HTTP 客户端，非空
     */
    @Autowired
    IotAlarmClosedLoopIT(
            IotDeviceMapper deviceMapper,
            IotBindingMapper bindingMapper,
            IotAlarmRuleMapper alarmRuleMapper,
            JdbcTemplate jdbcTemplate,
            TestRestTemplate restTemplate) {
        this.deviceMapper = deviceMapper;
        this.bindingMapper = bindingMapper;
        this.alarmRuleMapper = alarmRuleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：种子设备×2、BOUND 绑定、阈值与离线规则（mapper 直插定稿，审计列走库端默认值）。
     *
     * <p>离线设备 ONLINE 且 last_online_at 随实体直插为 10 分钟前（步骤⑥离线判定前提：距今超
     * offline_secs=60s 即候选；实体非空字段随 MP insert 落库，无需二次补写）。
     */
    @Test
    @Order(1)
    @DisplayName("种子：设备×2 + BOUND 绑定 + 阈值/离线规则（直插定稿，last_online_at 回填 10 分钟前）")
    void seedsDevicesBindingAndRules() {
        insertDevice(DEVICE_ID, DeviceStatus.ONLINE, null);
        insertDevice(
                OFFLINE_DEVICE_ID, DeviceStatus.ONLINE, OffsetDateTime.now().minusMinutes(10));

        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(DEVICE_ID);
        binding.setPatientId(PATIENT_ID);
        binding.setVisitId(VISIT_ID);
        binding.setBedId(2001L);
        binding.setWardId(WARD_ID);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        assertThat(bindingMapper.insert(binding)).as("BOUND 绑定种子插入成功").isEqualTo(1);

        thresholdRuleId = insertRule(
                "IT 阈值规则-心跳直通",
                AlarmRuleType.THRESHOLD,
                null,
                METRIC_CODE,
                ThresholdOp.GT,
                150,
                DURATION_SECS,
                10,
                null,
                AlarmLevel.WARNING);
        offlineRuleId = insertRule(
                "IT 离线规则-alm002",
                AlarmRuleType.OFFLINE,
                OFFLINE_DEVICE_ID,
                null,
                null,
                null,
                null,
                null,
                60,
                AlarmLevel.WARNING);
    }

    /**
     * 步骤②：阈值触发全链——第一批越限起算回合，等 1.2s 后第二批越限触发：断言 iot_alarm 恰 1 行
     * ACTIVE（trigger_count=1、绑定快照冗余 patient/visit/ward、trigger_value=181）且
     * q.it.iot.alarm.triggered 真实收帧（载荷 alarmNo/deviceId/metricCode/triggerValue/wardId
     * 五组件锚定——Task 7 组合路径单测敞口的本 IT 兜底断言）。
     *
     * <p>兜底通道 202 即 ingest 事务提交 + afterCommit 评估完成（同线程同步），告警行库态断言免轮询；
     * MQ 捕获为异步消费，轮询等待。
     */
    @Test
    @Order(2)
    @DisplayName("阈值触发全链：两批越限 + 持续时长达标 → ACTIVE 行（绑定快照冗余）+ triggered 帧真实送达")
    void thresholdBreachTriggersAlarmWithRealEventDelivery() throws Exception {
        postFallback(DEVICE_ID, METRIC_CODE, "180", "2026-09-10T06:00:00Z");
        Thread.sleep(EPISODE_WAIT_MILLIS);
        ResponseEntity<String> second = postFallbackForEntity(DEVICE_ID, METRIC_CODE, "181", "2026-09-10T06:00:10Z");
        assertThat(second.getStatusCode().value()).as("第二批兜底受理应 202").isEqualTo(202);

        Map<String, Object> row = awaitAlarmRow(thresholdRuleId, DEVICE_ID, "ACTIVE", 1);
        assertThat(row.get("trigger_value")).as("触发值 = 第二批越限原文").isEqualTo("181");
        assertThat(((Number) row.get("patient_id")).longValue())
                .as("患者列 = 绑定快照冗余（写入时富化）")
                .isEqualTo(PATIENT_ID);
        assertThat(row.get("visit_id")).as("就诊列 = 绑定快照冗余（CF-3 字符串）").isEqualTo(VISIT_ID);
        assertThat(((Number) row.get("ward_id")).longValue()).as("病区 = 绑定快照路由").isEqualTo(WARD_ID);

        // 捕获列表可能混入离线源帧（种子离线设备超判定秒，步骤②任一批评估即触发 DEVICE_OFFLINE
        // 告警发帧）——按规则 id 过滤阈值帧，与库行勾稽
        EventEnvelope triggered = awaitCapturedForRule(ItCaptureConfig.TRIGGERED, thresholdRuleId, "triggered 帧");
        JsonNode payload = triggered.payload();
        assertThat(payload.path("alarmNo").asText()).as("载荷告警号 = 库行告警号").isEqualTo(row.get("alarm_no"));
        assertThat(payload.path("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(payload.path("metricCode").asText()).isEqualTo(METRIC_CODE);
        assertThat(payload.path("triggerValue").asText()).isEqualTo("181");
        assertThat(payload.path("wardId").asLong()).isEqualTo(WARD_ID);
        alarmNo = payload.path("alarmNo").asText();
    }

    /**
     * 步骤③：重复越限聚合（抑制①）——新回合两批越限再触发：活跃行在挂 → Aggregated 仅计数，
     * 断言 trigger_count=2、规则行数仍 1、triggered 帧无新增（聚合路径不发事件）。
     */
    @Test
    @Order(3)
    @DisplayName("重复越限聚合：新回合触发命中活跃行 → trigger_count+1 不新行且无第二帧")
    void repeatedBreachAggregatesIntoActiveRow() throws Exception {
        postFallback(DEVICE_ID, METRIC_CODE, "182", "2026-09-10T06:01:00Z");
        Thread.sleep(EPISODE_WAIT_MILLIS);
        postFallback(DEVICE_ID, METRIC_CODE, "183", "2026-09-10T06:01:10Z");

        awaitUntil("聚合计数落库（trigger_count=2）", () -> triggerCountOf(thresholdRuleId, DEVICE_ID) == 2);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ?",
                Integer.class,
                thresholdRuleId,
                DEVICE_ID);
        assertThat(rows).as("抑制①聚合：不新增告警行").isEqualTo(1);
        assertThat(framesForRule(ItCaptureConfig.TRIGGERED, thresholdRuleId))
                .as("聚合路径不发布 triggered 事件，阈值规则帧数维持 1")
                .hasSize(1);
    }

    /**
     * 步骤④：确认/关闭 CAS + closed 事件——acknowledge → ACKNOWLEDGED（acknowledged_by=admin）；
     * close → CLOSED（closed_at/close_reason 落行）+ q.it.iot.alarm.closed 帧捕获；终态重关 409
     * IOT-1013（CAS 零行状态机违例）。
     */
    @Test
    @Order(4)
    @DisplayName("确认/关闭 CAS：acknowledge→close 双迁移 + closed 帧捕获 + 终态重关 409 IOT-1013")
    void acknowledgeAndCloseCasWithClosedEventDelivery() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        JsonNode acked = postJson("/api/v1/iot/alarms/" + alarmNo + "/acknowledge", bearer(token), null);
        assertThat(acked.path("status").asText()).as("确认后状态迁移 ACKNOWLEDGED").isEqualTo("ACKNOWLEDGED");

        ObjectNode closeBody = objectMapper.createObjectNode().put("reason", "IT 闭环收口");
        ResponseEntity<String> closed = restTemplate.exchange(
                "/api/v1/iot/alarms/" + alarmNo + "/close",
                HttpMethod.POST,
                new HttpEntity<>(closeBody, bearer(token)),
                String.class);
        assertThat(closed.getStatusCode().value())
                .as("关闭应 200，实况：%s", closed.getBody())
                .isEqualTo(200);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, close_reason, closed_by FROM iot.iot_alarm WHERE alarm_no = ?", alarmNo);
        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("close_reason")).as("关闭原因落行").isEqualTo("IT 闭环收口");

        EventEnvelope closedFrame = awaitCaptured(ItCaptureConfig.CLOSED, 1, "closed 帧");
        assertThat(closedFrame.payload().path("alarmNo").asText())
                .as("closed 载荷锚定同号")
                .isEqualTo(alarmNo);
        assertThat(closedFrame.payload().path("closeReason").asText()).isEqualTo("IT 闭环收口");

        ResponseEntity<String> reClose = restTemplate.exchange(
                "/api/v1/iot/alarms/" + alarmNo + "/close",
                HttpMethod.POST,
                new HttpEntity<>(closeBody, bearer(token)),
                String.class);
        assertThat(reClose.getStatusCode().value()).as("终态重关被拒").isEqualTo(409);
        assertThat(toNode(reClose.getBody()).path("errorCode").asText())
                .as("状态机违例码位 IOT-1013")
                .isEqualTo("IOT-1013");
    }

    /**
     * 步骤⑤：模拟回放不落库——POST /alarm-rules/{id}/simulate 回放步骤②的越限窗口：断言
     * scannedRows 覆盖四批行、触发重放非空，且告警行数不变（simulate 纯评估无库写）。
     */
    @Test
    @Order(5)
    @DisplayName("模拟回放：simulate 返回扫描行数与重放触发，且告警行数不变（不落库）")
    void simulateReplayDoesNotPersist() {
        String token = loginToken(ADMIN_LOGIN_NAME);
        Integer rowsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_alarm WHERE rule_id = ?", Integer.class, thresholdRuleId);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("from", "2026-09-10T05:59:00Z").put("to", "2026-09-10T06:02:00Z");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/iot/alarm-rules/" + thresholdRuleId + "/simulate",
                HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)),
                String.class);
        assertThat(resp.getStatusCode().value())
                .as("模拟回放应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode result = toNode(resp.getBody());
        assertThat(result.path("scannedRows").asInt()).as("回放覆盖步骤②③四批遥测行").isGreaterThanOrEqualTo(4);
        assertThat(result.path("triggers").size()).as("越限回合重放应有触发点").isGreaterThanOrEqualTo(1);

        Integer rowsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_alarm WHERE rule_id = ?", Integer.class, thresholdRuleId);
        assertThat(rowsAfter).as("模拟回放不落库，告警行数不变").isEqualTo(rowsBefore);
    }

    /**
     * 步骤⑥：离线告警与衍生抑制——②离线源：ONLINE 设备 last_online_at 超离线判定秒 → 批次评估
     * 惰性扫描落 DEVICE_OFFLINE 告警（离线源自身不受抑制③约束）；③衍生抑制：该设备活跃离线
     * 告警在挂时，遥测衍生的阈值新发被 OfflineSuppressed 拦截（同规则同设备零告警行）。
     */
    @Test
    @Order(6)
    @DisplayName("离线告警与衍生抑制：OFFLINE 规则落 DEVICE_OFFLINE 行，活跃离线告警抑制遥测衍生新发")
    void offlineAlarmFiresAndSuppressesTelemetryDerivedAlarms() throws Exception {
        // 批次评估触发离线惰性扫描（评估随 ingest afterCommit 调起）
        postFallback(OFFLINE_DEVICE_ID, "vital.temp-it", "36.5", "2026-09-10T06:03:00Z");
        awaitUntil("离线告警落行（DEVICE_OFFLINE）", () -> alarmCountOf(offlineRuleId, OFFLINE_DEVICE_ID) == 1);
        Map<String, Object> offlineRow = jdbcTemplate.queryForMap(
                "SELECT metric_code, trigger_value FROM iot.iot_alarm WHERE rule_id = ?", offlineRuleId);
        assertThat(offlineRow.get("metric_code")).as("离线告警指标 = 固定占位词").isEqualTo("DEVICE_OFFLINE");

        // 衍生抑制：首批起算回合 → 等待 → 第二批触发尝试被抑制③拦截
        postFallback(OFFLINE_DEVICE_ID, METRIC_CODE, "180", "2026-09-10T06:03:10Z");
        Thread.sleep(EPISODE_WAIT_MILLIS);
        postFallback(OFFLINE_DEVICE_ID, METRIC_CODE, "181", "2026-09-10T06:03:20Z");
        // 锚点批（非越限值复位回合态，且离线行计数稳定可见）后复核抑制终局
        postFallback(OFFLINE_DEVICE_ID, METRIC_CODE, "100", "2026-09-10T06:03:30Z");
        Thread.sleep(EPISODE_WAIT_MILLIS);
        assertThat(alarmCountOf(thresholdRuleId, OFFLINE_DEVICE_ID))
                .as("抑制③：活跃离线告警在挂时遥测衍生阈值新发被拦截（零行）")
                .isZero();
    }

    // ---------------------------------------------------------------- 种子与断言助手

    /** 设备种子直插（audit 列走库端默认；lastOnlineAt 可空——非空时用例自补写库态）。 */
    private void insertDevice(String deviceId, DeviceStatus status, OffsetDateTime lastOnlineAt) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(deviceId);
        device.setDeviceName("IT 告警闭环种子设备");
        device.setDeviceType("monitor");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(status);
        device.setLastOnlineAt(lastOnlineAt);
        assertThat(deviceMapper.insert(device)).as("设备种子插入成功：" + deviceId).isEqualTo(1);
    }

    /**
     * 规则种子直插并回读雪花 id。
     *
     * @return 落行后的规则 id（后续步骤断言锚）
     */
    private long insertRule(
            String ruleName,
            AlarmRuleType ruleType,
            String deviceId,
            String metricCode,
            ThresholdOp compareOp,
            Integer thresholdValue,
            Integer durationSecs,
            Integer recoveryBand,
            Integer offlineSecs,
            AlarmLevel alarmLevel) {
        IotAlarmRuleEntity rule = new IotAlarmRuleEntity();
        rule.setRuleName(ruleName);
        rule.setRuleType(ruleType);
        rule.setDeviceId(deviceId);
        rule.setMetricCode(metricCode);
        rule.setCompareOp(compareOp);
        rule.setThresholdValue(thresholdValue == null ? null : java.math.BigDecimal.valueOf(thresholdValue));
        rule.setDurationSecs(durationSecs);
        rule.setRecoveryBand(recoveryBand == null ? null : java.math.BigDecimal.valueOf(recoveryBand));
        rule.setOfflineSecs(offlineSecs);
        rule.setAlarmLevel(alarmLevel);
        rule.setEnabled(true);
        assertThat(alarmRuleMapper.insert(rule)).as("规则种子插入成功：" + ruleName).isEqualTo(1);
        return rule.getId();
    }

    /** 兜底通道受理（202；occurredAt 固定历史时点防时钟偏差 SUSPECT 干扰触发值断言）。 */
    private void postFallback(String deviceId, String metricCode, String value, String occurredAt) {
        ResponseEntity<String> resp = postFallbackForEntity(deviceId, metricCode, value, occurredAt);
        assertThat(resp.getStatusCode().value())
                .as("兜底受理应 202，实况：%s", resp.getBody())
                .isEqualTo(202);
    }

    /** 兜底通道 POST（返回原始响应供状态码断言；token 走独立鉴权链不携 Bearer）。 */
    private ResponseEntity<String> postFallbackForEntity(
            String deviceId, String metricCode, String value, String occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId)
                .put("metricCode", metricCode)
                .put("value", value)
                .put("occurredAt", occurredAt);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Iot-Fallback-Token", TEST_FALLBACK_TOKEN);
        return restTemplate.postForEntity("/ingest/iotda-fallback", new HttpEntity<>(body, headers), String.class);
    }

    /** Bearer 请求头（确认/关闭/模拟端点鉴权输入）。 */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** 无体响应解析兜底（204/空体回 MISSING 单例，NursingVitalSignFlowIT 同型收口）。 */
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

    /** 规则+设备维度告警行数（抑制断言锚）。 */
    private int alarmCountOf(long ruleId, String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ?",
                Integer.class,
                ruleId,
                deviceId);
        return count == null ? 0 : count;
    }

    /** 规则+设备维度活跃行触发计数（聚合断言锚；无行返回 -1 驱动轮询继续）。 */
    private int triggerCountOf(long ruleId, String deviceId) {
        List<Integer> counts = jdbcTemplate.query(
                "SELECT trigger_count FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ?",
                (rs, rowNum) -> rs.getInt(1),
                ruleId,
                deviceId);
        return counts.isEmpty() ? -1 : counts.get(0);
    }

    /**
     * 等待规则+设备维度目标状态告警行出现并整行返回（触发链为 ingest 同线程 afterCommit，库态
     * 通常即时；轮询兜底消费/调度抖动）。
     */
    private Map<String, Object> awaitAlarmRow(long ruleId, String deviceId, String status, int minTriggerCount) {
        awaitUntil("告警行落位（rule=" + ruleId + "，device=" + deviceId + "，status=" + status + "）", () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ? AND status = ?"
                                + " AND trigger_count >= ?",
                        ruleId,
                        deviceId,
                        status,
                        minTriggerCount)
                .isEmpty());
        return jdbcTemplate.queryForMap(
                "SELECT * FROM iot.iot_alarm WHERE rule_id = ? AND device_id = ? AND status = ?"
                        + " AND trigger_count >= ?",
                ruleId,
                deviceId,
                status,
                minTriggerCount);
    }

    /** 等待阈值规则维度的 triggered 帧出现并返回（离线源帧混入捕获列表，按 ruleId 过滤勾稽）。 */
    private EventEnvelope awaitCapturedForRule(List<EventEnvelope> captured, long ruleId, String description) {
        awaitUntil(description + "收帧达标（rule=" + ruleId + "）", () -> !framesForRule(captured, ruleId)
                .isEmpty());
        return framesForRule(captured, ruleId).get(0);
    }

    /** 捕获列表中按规则 id 过滤（载荷 ruleId 经全局 Jackson Long→String 承载，asLong 兼容文本值）。 */
    private static List<EventEnvelope> framesForRule(List<EventEnvelope> captured, long ruleId) {
        return captured.stream()
                .filter(env -> env.payload().path("ruleId").asLong() == ruleId)
                .toList();
    }

    /** 等待捕获列表达到目标帧数并返回首帧（MQ 异步消费的确定性等待）。 */
    private EventEnvelope awaitCaptured(List<EventEnvelope> captured, int expectedSize, String description) {
        awaitUntil(description + "收帧达标（" + expectedSize + "）", () -> captured.size() >= expectedSize);
        return captured.get(0);
    }

    /** 轮询等待业务条件成立（超时后最终复核一次以输出断言上下文，IotTelemetryPipelineIT 同款）。 */
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

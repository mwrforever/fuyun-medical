package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.fuyun.nursing.api.NursingTaskLinkagePort;
import com.fuyun.nursing.api.NursingTaskLinkageRequest;
import com.fuyun.nursing.api.NursingTaskLinkageResult;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-3 验收锚点⑦（IoT 联动→M05 护理任务回接，真栈全链）：NURSING_TASK 动作联动规则预置→
 * 越限遥测经 HTTP 兜底通道注入→告警引擎真实触发（iot.alarm.triggered）→LinkageExecutor 消费
 * 匹配→nursing_task 落行（source=IOT_LINKAGE）+ iot_linkage_log SUCCESS 断言→同 linkageNo
 * 重复触发幂等（业务回查零新建，Task 12 裁决口径：幂等键=source+source_ref）。
 *
 * <p><b>触发链形态（IotLinkageFlowIT 同款真栈）</b>：HTTP 兜底通道注入越限遥测→引擎触发告警
 * （患者/就诊快照自 BOUND 绑定冗余）→联动执行器经 nursing api 端口进程内直调幂等创建任务。
 * 重复触发幂等面：LinkageExecutor 的生产入口（NursingTaskLinkagePort）以同 linkageNo 直调——
 * 模拟链路重投/人工重推场景（新告警必新 linkageNo，重放面唯一载体=端口幂等回查）。
 *
 * <p>容器三件套类级独占（GC9 红线，IotLinkageFlowIT :84-100 逐字同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LinkageNursingTaskIT extends FuyunStackITBase {

    /** TimescaleDB 容器：联动规则/日志与护理任务断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：越限回合标记/发号器载体 */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：告警自事件消费链载体（本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假兜底共享密钥（fuyun.iot.fallback.token，仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_FALLBACK_TOKEN = "it-iot-lnk-task-fallback-token";

    /** 联动锚定设备号 */
    private static final String DEVICE_ID = "it-lnk-task-001";

    /** 阈值规则指标编码（词表外直通行，防 V1008 种子规则串扰） */
    private static final String METRIC_CODE = "vital.lnk-task-it";

    /** 病区路由断言值（iot 域病区 id 数字串——nursing 侧承载为文本编码，标识空间申报口径） */
    private static final long WARD_ID = 1001L;

    /** 绑定快照断言值：患者 ID（雪花段外固定值） */
    private static final long PATIENT_ID = 920801L;

    /** 绑定快照断言值：就诊号（CF-3 I 型 14 位） */
    private static final String VISIT_ID = "I2026100300008";

    /** 联动规则直插主键（MP ASSIGN_ID 应用层雪花，jdbcTemplate 直插须自带；雪花段外种子保留段） */
    private static final long LINKAGE_RULE_ID = 900031L;

    /** 消费链路等待上限（兜底→引擎→消费→联动异步收敛） */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 跨用例链路状态（JUnit 每用例新实例，联动号经 static 传递） */
    private static String linkageNo = "";

    private static String taskNo = "";

    /** 设备档案 mapper：种子直插 */
    @Autowired
    private IotDeviceMapper deviceMapper;

    /** 绑定 mapper：BOUND 绑定种子直插（患者/就诊快照承载） */
    @Autowired
    private IotBindingMapper bindingMapper;

    /** 告警规则 mapper：阈值规则种子直插 */
    @Autowired
    private IotAlarmRuleMapper alarmRuleMapper;

    /** 护理任务联动端口：重复触发幂等面的生产入口直调（LinkageExecutor 同款调用面） */
    @Autowired
    private NursingTaskLinkagePort nursingTaskPort;

    /** JDBC 模板：联动规则播种与断言通道 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：兜底通道调用 */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 注入兜底通道共享密钥（FuyunStackITBase 的密钥三元组对本类继续生效）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerFallbackProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.iot.fallback.token", () -> TEST_FALLBACK_TOKEN);
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

    /** 兜底通道受理断言（202；IotLinkageFlowIT postFallback 同型）。 */
    private void postFallback(String value, String occurredAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", DEVICE_ID)
                .put("metricCode", METRIC_CODE)
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

    /** 轮询等待业务条件成立（消费链异步收敛；超时附死信诊断）。 */
    private void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!condition.getAsBoolean()) {
            var deadLetters = jdbcTemplate.queryForList(
                    "SELECT event_type, fail_reason FROM integration.dead_letter ORDER BY created_at DESC LIMIT 5");
            throw new IllegalStateException(description + "——等待超时，死信近帧=" + deadLetters);
        }
    }

    @Test
    @Order(1)
    @DisplayName("种子：设备 + BOUND 绑定（患者/就诊快照）+ 阈值规则 + NURSING_TASK 联动规则")
    void seedsDeviceBindingAndRules() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceName("IT 联动任务种子设备");
        device.setDeviceType("monitor");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(device)).as("设备种子插入成功").isEqualTo(1);

        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(DEVICE_ID);
        binding.setPatientId(PATIENT_ID);
        binding.setVisitId(VISIT_ID);
        binding.setBedId(2001L);
        binding.setWardId(WARD_ID);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        assertThat(bindingMapper.insert(binding)).as("BOUND 绑定种子插入成功").isEqualTo(1);

        IotAlarmRuleEntity rule = new IotAlarmRuleEntity();
        rule.setRuleName("IT 联动任务阈值规则");
        rule.setRuleType(AlarmRuleType.THRESHOLD);
        rule.setMetricCode(METRIC_CODE);
        rule.setCompareOp(ThresholdOp.GT);
        rule.setThresholdValue(java.math.BigDecimal.valueOf(150));
        rule.setDurationSecs(1);
        rule.setRecoveryBand(java.math.BigDecimal.valueOf(10));
        rule.setAlarmLevel(AlarmLevel.WARNING);
        rule.setEnabled(true);
        assertThat(alarmRuleMapper.insert(rule)).as("阈值规则种子插入成功").isEqualTo(1);

        // NURSING_TASK 动作联动规则（词表外指标精确命中；action_config 携任务类型/标题透传，
        // 直插为 IotLinkageFlowIT 批准口径——词表校验面归服务层）
        jdbcTemplate.update(
                "INSERT INTO iot.linkage_rule (id, rule_name, trigger_source, trigger_condition, action_type,"
                        + " action_config, enabled)"
                        + " VALUES (?, ?, 'ALARM_TRIGGERED', ?::jsonb, 'NURSING_TASK', ?::jsonb, true)",
                LINKAGE_RULE_ID,
                "IT 联动-护理任务",
                "{\"metric_code\":\"" + METRIC_CODE + "\"}",
                "{\"taskType\":\"IOT_LINKAGE\",\"title\":\"IT 联动任务标题\"}");
    }

    @Test
    @Order(2)
    @DisplayName("触发全链：越限兜底注入→告警触发→联动执行→nursing_task 落行（IOT_LINKAGE）+ linkage_log SUCCESS")
    void alarmDrivesNursingTaskCreation() throws Exception {
        // 两批越限（间隔 1.2s 达标持续时长 1s）→ 引擎真实触发告警
        postFallback("180", "2026-10-03T01:00:00Z");
        Thread.sleep(1_200L);
        postFallback("181", "2026-10-03T01:00:10Z");

        awaitUntil("联动日志 SUCCESS 行落位", () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM iot.iot_linkage_log WHERE rule_id = ? AND action_result = 'SUCCESS'",
                        LINKAGE_RULE_ID)
                .isEmpty());
        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT linkage_no, action_type, action_result, retry_count, trigger_source FROM iot.iot_linkage_log"
                        + " WHERE rule_id = ? AND action_result = 'SUCCESS'",
                LINKAGE_RULE_ID);
        linkageNo = (String) logRow.get("linkage_no");
        assertThat(linkageNo).as("联动号冻结形态 LG+13 位数字").matches("LG\\d{13}");
        assertThat(logRow.get("action_type")).as("动作类型快照=NURSING_TASK").isEqualTo("NURSING_TASK");
        assertThat(logRow.get("action_result")).as("首试即成").isEqualTo("SUCCESS");
        assertThat(((Number) logRow.get("retry_count")).intValue())
                .as("首试重试计数 0")
                .isZero();

        // 联动任务落行断言（Task 12 裁决口径：source=IOT_LINKAGE + source_ref=linkageNo）
        awaitUntil("联动任务行落位（source_ref=linkageNo）", () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM nursing.nursing_task WHERE source = 'IOT_LINKAGE' AND source_ref = ?"
                                + " AND deleted = 0",
                        linkageNo)
                .isEmpty());
        Map<String, Object> taskRow = jdbcTemplate.queryForMap(
                "SELECT task_no, task_type, ward_id, visit_id, patient_id, status FROM nursing.nursing_task"
                        + " WHERE source = 'IOT_LINKAGE' AND source_ref = ? AND deleted = 0",
                linkageNo);
        taskNo = (String) taskRow.get("task_no");
        assertThat(taskNo).as("任务号契约 TK+yyyyMMdd+5 位流水").matches("TK\\d{13}");
        assertThat(taskRow.get("task_type")).as("任务类型=规则 action_config 透传").isEqualTo("IOT_LINKAGE");
        assertThat(taskRow.get("ward_id"))
                .as("病区=iot 域病区 id 数字串（标识空间申报口径，跟随触发源病区）")
                .isEqualTo(String.valueOf(WARD_ID));
        assertThat(taskRow.get("visit_id")).as("就诊号=告警行绑定快照").isEqualTo(VISIT_ID);
        assertThat(((Number) taskRow.get("patient_id")).longValue())
                .as("患者=告警行绑定快照")
                .isEqualTo(PATIENT_ID);
        assertThat(taskRow.get("status")).as("联动任务初始态").isEqualTo("PENDING");
    }

    @Test
    @Order(3)
    @DisplayName("重复触发幂等：同 linkageNo 经生产端口重调（链路重投形态），回查原任务零新建")
    void duplicateTriggerIsIdempotentByLinkageNo() {
        assertThat(linkageNo).as("前置用例应已捕获联动号").isNotBlank();
        int tasksBefore = taskCountByLinkageNo();
        // 生产入口直调重放（LinkageExecutor 同款调用面——新告警必新 linkageNo，重放面唯一载体）
        NursingTaskLinkageResult replay = nursingTaskPort.createTask(new NursingTaskLinkageRequest(
                linkageNo,
                String.valueOf(WARD_ID),
                PATIENT_ID,
                VISIT_ID,
                "IOT_LINKAGE",
                "IT 联动任务标题",
                OffsetDateTime.now()));
        assertThat(replay.created()).as("同 linkageNo 重调应回查原任务（created=false）").isFalse();
        assertThat(replay.taskNo()).as("回查返回原任务号").isEqualTo(taskNo);
        assertThat(taskCountByLinkageNo()).as("同 linkageNo 任务行仍恰一行（业务回查幂等）").isEqualTo(tasksBefore);
    }

    /** 该联动号任务行数（幂等断言锚）。 */
    private int taskCountByLinkageNo() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_task WHERE source = 'IOT_LINKAGE' AND source_ref = ?"
                        + " AND deleted = 0",
                Integer.class,
                linkageNo);
        return n == null ? 0 : n;
    }
}

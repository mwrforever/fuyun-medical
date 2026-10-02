package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.constants.TimeConstants;
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
 * 绑定 visit_id 类型改造验收锚点 IT（TASK.md W-10 闭合锚，P2 PR-2 Task 18）：V1006 迁移后
 * iot_binding / iot_telemetry visit_id 库端类型实查断言 + 演示夹具重插断言 + 绑定 API 全链
 * （bind 校验链四环 / unbind 双 CAS / iot.binding.changed 事件真实投递）。
 *
 * <p>业务意图：W-10 改造（BIGINT → CF-3 定长 14 位 VARCHAR(14)）此前仅单测与迁移脚本自证，
 * 本类以真实 TimescaleDB 容器重放迁移后 information_schema 实查列类型（防"脚本写了、库里没改"
 * 的漂移），并沿真实 HTTP 面走通绑定域全链——bind 校验链（设备存在→非停用→无 BOUND→患者可用→
 * 在途就诊）与 unbind 双 CAS（BOUND→UNBINDING→UNBOUND），事件经 fy.topic 至治理捕获队列真实
 * 收帧，验证载荷八组件与 BIND/UNBIND 两态语义。
 *
 * <p>四步断言按 @Order 串联（绑定行状态跨步累积属业务链路语义）：①迁移形态实查（两表列类型 +
 * V1006 重插夹具行存在）；②bind 负路径四连（设备不存在 404 IOT-1006 / 停用设备 409 IOT-1007 /
 * 冻结患者 409 IOT-1011 / 无在途就诊 409 IOT-1011）；③正路径绑定（真实入院链（W-34 后入区
 * 唯一写入面：inpatient 四步→admitted/bed.changed 事件投影在区行）制造在途就诊 →
 * bind 200 BOUND、visit_id 14 位字符串落库回读 → 重复绑定 409 IOT-1010 → BIND 帧捕获）；
 * ④解绑链（空白原因 400 IOT-1010 → 合法解绑双 CAS 至 UNBOUND → UNBIND 帧捕获（患者/就诊置
 * null）→ 重复解绑 409 IOT-1010 → 解绑后可再绑（uk BOUND 部分索引释放））。
 *
 * <p>容器三件套与 {@link IotTelemetryPipelineIT} 完全同款（类级独占 + @ServiceConnection +
 * it/rabbitmq.conf 挂载）；登录/POST 助手复用 {@link FuyunStackITBase}。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotBindingMigrationIT extends FuyunStackITBase {

    /** TimescaleDB 容器：V1006 迁移重放目标库与列类型实查来源；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：上下文完整装配所需（患者缓存 L2/幂等构件），本类不直接断言 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：fy.topic 事件扇出与治理捕获队列载体（本类独占 broker） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** V1006 重插演示夹具行主键与就诊号（迁移脚本字面值，断言锚） */
    private static final long FIXTURE_BINDING_ID = 900001L;

    private static final String FIXTURE_VISIT_ID = "I2026090100001";

    /** CF-3 定长 14 位字符串（W-10 改造目标形态） */
    private static final int CF3_VISIT_ID_LENGTH = 14;

    /** 绑定链正向设备号（步骤①直插 ONLINE 档案） */
    private static final String DEVICE_ID = "it-v10-001";

    /** 停用设备号（bind 校验链第二环负样本） */
    private static final String DISABLED_DEVICE_ID = "it-v10-002";

    /** 正常患者主索引（直插 NORMAL 档案夹具，NursingPatientContextIT 同型形态） */
    private static final long NORMAL_PATIENT_ID = 950001L;

    /** 冻结患者主索引（resolve blocked 负样本） */
    private static final long FROZEN_PATIENT_ID = 950002L;

    /** 负路径占位就诊号（bind 校验链第四环负样本请求体载体，不校验实存；真实就诊号由步骤③入院链签发） */
    private static final String PLACEHOLDER_VISIT_ID = "I2026092500011";

    /** 病区 ID（绑定落行归属，与护理入区 "W01" 编码列分域不互查） */
    private static final long WARD_ID = 1001L;

    /** 入院链病区编码（护理投影归属；W-34 后入区在途由 admitted 事件投影在册行承载） */
    private static final String NURSING_WARD_CODE = "W-IT-9501";

    /** 入院链床位（inpatient.bed 零种子，IT 自备主数据；NursingPatientContextIT 同型） */
    private static final long CHAIN_BED_ID = 950101L;

    private static final String CHAIN_BED_NO = "IT50-01";

    /** 正路径绑定就诊号（步骤③入院链登记确认签发 I 型 14 位；断言锚经 static 跨用例传递） */
    private static String boundVisitId = "";

    /** 捕获队列名（治理声明的 "it" 消费者模块队列：q.it.iot.binding.changed） */
    private static final String Q_BINDING_CHANGED =
            MessagingConstants.QUEUE_PREFIX + "it." + IotMessagingConstants.EVENT_BINDING_CHANGED;

    /** 绑定变更事件捕获列表（BIND/UNBIND 两帧，按序累积） */
    private static final List<EventEnvelope> BINDING_CHANGED = new CopyOnWriteArrayList<>();

    /** 异步消费链路轮询等待上限 */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(30);

    /** DB/MQ 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 捕获队列声明（A.5-4 治理红线：禁测试自声明裸队列——经 MessagingGovernance 声明）。 */
    @TestConfiguration
    static class ItCaptureConfig {

        @Bean
        Declarables itBindingCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", IotMessagingConstants.EVENT_BINDING_CHANGED));
        }

        @Bean
        ItBindingCaptureListener itBindingCaptureListener(EventEnvelopeCodec codec) {
            return new ItBindingCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（测试侧轻量：收帧入列表，不登记 received_event）。 */
    static class ItBindingCaptureListener {

        private final EventEnvelopeCodec codec;

        ItBindingCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {Q_BINDING_CHANGED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (IotMessagingConstants.EVENT_BINDING_CHANGED.equals(envelope.eventType())) {
                BINDING_CHANGED.add(envelope);
            }
        }
    }

    /** 设备档案 mapper：种子直插（IotTelemetryPipelineIT 同款定稿） */
    private final IotDeviceMapper deviceMapper;

    /** JDBC 模板：列类型 information_schema 实查与绑定行库态断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** 随机端口 HTTP 客户端：绑定/解绑端点调用 */
    private final TestRestTemplate restTemplate;

    /**
     * 构造器注入（backend 宪法 A.1-7）。
     *
     * @param deviceMapper 设备档案 mapper，非空
     * @param jdbcTemplate JDBC 模板，非空
     * @param restTemplate 随机端口 HTTP 客户端，非空
     */
    @Autowired
    IotBindingMigrationIT(IotDeviceMapper deviceMapper, JdbcTemplate jdbcTemplate, TestRestTemplate restTemplate) {
        this.deviceMapper = deviceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    /**
     * 步骤①：迁移形态实查——information_schema 断言 iot_binding.visit_id 与 iot_telemetry.visit_id
     * 均为 VARCHAR(14)（V1006 步骤② 类型改造的库端落位证据），且 V1006 步骤③ 重插演示夹具行
     * （id=900001，visit_id='I2026090100001'，BOUND）在位。
     */
    @Test
    @Order(1)
    @DisplayName("迁移实查：两表 visit_id VARCHAR(14)（information_schema）+ V1006 重插夹具行在位")
    void assertsVisitIdColumnTypeAndFixtureRow() {
        assertVarchar14("iot_binding", "visit_id");
        assertVarchar14("iot_telemetry", "visit_id");

        // 数据库读操作：V1006 步骤③ 重插演示夹具行存在性 + visit_id 文本形态回读
        Map<String, Object> fixture = jdbcTemplate.queryForMap(
                "SELECT visit_id, status, patient_id FROM iot.iot_binding WHERE id = ?", FIXTURE_BINDING_ID);
        assertThat(fixture.get("visit_id")).as("夹具行 visit_id 应重插为 CF-3 14 位字符串").isEqualTo(FIXTURE_VISIT_ID);
        assertThat(((String) fixture.get("visit_id")).length()).isEqualTo(CF3_VISIT_ID_LENGTH);
        assertThat(fixture.get("status")).isEqualTo("BOUND");
        assertThat(((Number) fixture.get("patient_id")).longValue())
                .as("夹具行患者 1 保留")
                .isEqualTo(1L);
    }

    /**
     * 步骤②：bind 校验链负路径——设备不存在 404 IOT-1006（第一环）、停用设备 409 IOT-1007
     * （第二环）、冻结患者 409 IOT-1011（第三环 blocked）、无在途就诊患者 409 IOT-1011（第四环）。
     */
    @Test
    @Order(2)
    @DisplayName("bind 校验链负路径：404 IOT-1006 / 409 IOT-1007 / 冻结与无在途就诊 409 IOT-1011")
    void bindValidationChainRejectsNegativeCases() {
        String token = loginToken(ADMIN_LOGIN_NAME);

        // 种子设备两态（ONLINE 正样本 + DISABLED 负样本）与患者两态（NORMAL / FROZEN 直插夹具）
        insertDevice(DEVICE_ID, DeviceStatus.ONLINE);
        insertDevice(DISABLED_DEVICE_ID, DeviceStatus.DISABLED);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                NORMAL_PATIENT_ID,
                "IT W-10 正常患者");
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'FROZEN', 'WINDOW')",
                FROZEN_PATIENT_ID,
                "IT W-10 冻结患者");

        // 第一环：设备不存在 → 404 IOT-1006
        ResponseEntity<String> noDevice = postForEntity(
                token, "/api/v1/iot/bindings", bindBody("it-v10-unknown", NORMAL_PATIENT_ID, PLACEHOLDER_VISIT_ID));
        assertThat(noDevice.getStatusCode().value()).as("设备不存在应 404").isEqualTo(404);
        assertThat(toNode(noDevice.getBody()).path("errorCode").asText()).isEqualTo("IOT-1006");

        // 第二环：停用设备 → 409 IOT-1007
        ResponseEntity<String> disabled = postForEntity(
                token, "/api/v1/iot/bindings", bindBody(DISABLED_DEVICE_ID, NORMAL_PATIENT_ID, PLACEHOLDER_VISIT_ID));
        assertThat(disabled.getStatusCode().value()).as("停用设备应 409").isEqualTo(409);
        assertThat(toNode(disabled.getBody()).path("errorCode").asText()).isEqualTo("IOT-1007");

        // 第三环：冻结患者 → 409 IOT-1011（resolve blocked）
        ResponseEntity<String> frozen = postForEntity(
                token, "/api/v1/iot/bindings", bindBody(DEVICE_ID, FROZEN_PATIENT_ID, PLACEHOLDER_VISIT_ID));
        assertThat(frozen.getStatusCode().value()).as("冻结患者应 409").isEqualTo(409);
        assertThat(toNode(frozen.getBody()).path("errorCode").asText()).isEqualTo("IOT-1011");

        // 第四环：正常患者但无在途就诊 → 409 IOT-1011（各注册模块 hasOngoingVisit 全无命中）
        ResponseEntity<String> noVisit = postForEntity(
                token, "/api/v1/iot/bindings", bindBody(DEVICE_ID, NORMAL_PATIENT_ID, PLACEHOLDER_VISIT_ID));
        assertThat(noVisit.getStatusCode().value()).as("无在途就诊应 409").isEqualTo(409);
        assertThat(toNode(noVisit.getBody()).path("errorCode").asText()).isEqualTo("IOT-1011");
    }

    /**
     * 步骤③：正路径绑定全链——真实入院链（W-34 后入区唯一写入面：inpatient 四步→admitted/
     * bed.changed 事件投影在区行）制造在途就诊 → bind 200（BindingVO BOUND、visitId
     * 14 位字符串原样承载）→ 库态回读类型断言 → 重复绑定 409 IOT-1010（第二环前置拒绝）→
     * q.it.iot.binding.changed 真实收 BIND 帧（载荷八组件锚定，patientId/visitId 在位）。
     */
    @Test
    @Order(3)
    @DisplayName("bind 正路径：真实在途就诊 → 200 BOUND（visit_id 字符串落库）+ 重复绑定 409 + BIND 帧送达")
    void bindHappyPathPersistsCf3VisitIdAndPublishesEvent() {
        String token = loginToken(ADMIN_LOGIN_NAME);

        // 在途就诊制造（W-34 后入区唯一写入面）：inpatient 入院四步真链 → admitted/bed.changed
        // 事件驱动护理投影在册行 → NursingOngoingVisitQuery（deleted=0 计数）命中
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                CHAIN_BED_ID,
                CHAIN_BED_NO,
                NURSING_WARD_CODE);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", NORMAL_PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", NURSING_WARD_CODE)
                .put("issuedDoctorId", "3");
        String admissionNo = toNode(postForEntity(token, "/api/v1/inpatient/admissions", create)
                        .getBody())
                .path("admissionNo")
                .asText();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", NURSING_WARD_CODE)
                .put("targetBedId", CHAIN_BED_ID)
                // 期望入住日按北京钟面取当日（时区红线：裸 now() 在 CI UTC 深夜窗错归前一日）
                .put("expectDate", LocalDate.now(TimeConstants.HEALTHCARE_TZ).toString());
        ResponseEntity<String> scheduled =
                postForEntity(token, "/api/v1/inpatient/admissions/" + admissionNo + "/schedule", schedule);
        assertThat(scheduled.getStatusCode().is2xxSuccessful())
                .as("预约入院应 2xx，实况：%s", scheduled.getBody())
                .isTrue();
        boundVisitId = toNode(postForEntity(
                                token,
                                "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                                objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                        .getBody())
                .path("visitId")
                .asText();
        assertThat(boundVisitId).as("登记确认应签发 I 型 14 位 visit_id").hasSize(14);
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", NURSING_WARD_CODE).put("bedId", CHAIN_BED_ID).put("nursingLevel", "NORMAL");
        ResponseEntity<String> admitted =
                postForEntity(token, "/api/v1/inpatient/visits/" + boundVisitId + "/admit-ward", admit);
        assertThat(admitted.getStatusCode().is2xxSuccessful())
                .as("入科确认应 2xx（admitted/bed.changed 发布），实况：%s", admitted.getBody())
                .isTrue();
        awaitWardPatientRow();

        JsonNode bound =
                postJson("/api/v1/iot/bindings", bearer(token), bindBody(DEVICE_ID, NORMAL_PATIENT_ID, boundVisitId));
        assertThat(bound.path("status").asText()).as("绑定后状态 BOUND").isEqualTo("BOUND");
        assertThat(bound.path("visitId").asText())
                .as("出网 visit_id = CF-3 14 位字符串原样承载")
                .isEqualTo(boundVisitId);
        assertThat(bound.path("patientId").asLong()).as("落行患者 = resolve 归一主档").isEqualTo(NORMAL_PATIENT_ID);

        // 数据库读操作：库态回读——visit_id 文本列精确等值（非数值隐式转换形态）
        String persisted = jdbcTemplate.queryForObject(
                "SELECT visit_id FROM iot.iot_binding WHERE device_id = ? AND status = 'BOUND'",
                String.class,
                DEVICE_ID);
        assertThat(persisted).as("BOUND 行 visit_id 字符串落库").isEqualTo(boundVisitId);

        // 第二环重复绑定拒绝：同设备再指一个患者 → 409 IOT-1010
        ResponseEntity<String> dup =
                postForEntity(token, "/api/v1/iot/bindings", bindBody(DEVICE_ID, NORMAL_PATIENT_ID, boundVisitId));
        assertThat(dup.getStatusCode().value()).as("重复绑定应 409").isEqualTo(409);
        assertThat(toNode(dup.getBody()).path("errorCode").asText()).isEqualTo("IOT-1010");

        awaitCaptured(1, "BIND 帧");
        JsonNode payload = BINDING_CHANGED.get(0).payload();
        assertThat(payload.path("changeType").asText()).as("事件语义 = BIND").isEqualTo("BIND");
        assertThat(payload.path("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(payload.path("patientId").asLong()).isEqualTo(NORMAL_PATIENT_ID);
        assertThat(payload.path("visitId").asText()).isEqualTo(boundVisitId);
        assertThat(payload.path("wardId").asLong()).isEqualTo(WARD_ID);
        assertThat(payload.path("bindType").asText()).isEqualTo("FIXED");
    }

    /**
     * 步骤④：unbind 双 CAS 全链——空白原因 400 IOT-1010（服务层原因强制）→ 合法解绑 200（
     * BOUND→UNBINDING→UNBOUND 同事务双迁移，库态终局 UNBOUND + unbind_reason 留痕）→ UNBIND 帧
     * 捕获（患者/就诊按契约置 null）→ 重复解绑 409 IOT-1010 → 解绑后同设备可再绑（BOUND 部分
     * 唯一索引释放，双 CAS 完整闭环）。
     */
    @Test
    @Order(4)
    @DisplayName("unbind 双 CAS：空白原因 400 → UNBOUND 落行 + UNBIND 帧（患者/就诊 null）→ 重复解绑 409 → 可再绑")
    void unbindDoubleCasWithEventAndRebind() {
        String token = loginToken(ADMIN_LOGIN_NAME);

        // 原因强制：空白原因 → 400 IOT-1010（服务层显式拒，Bean Validation 之外兜底面）
        ResponseEntity<String> blank = postForEntity(
                token,
                "/api/v1/iot/bindings/" + DEVICE_ID + "/unbind",
                objectMapper.createObjectNode().put("reason", "  "));
        assertThat(blank.getStatusCode().value()).as("空白原因应 400").isEqualTo(400);
        assertThat(toNode(blank.getBody()).path("errorCode").asText()).isEqualTo("IOT-1010");

        ResponseEntity<String> unbound = postForEntity(
                token,
                "/api/v1/iot/bindings/" + DEVICE_ID + "/unbind",
                objectMapper.createObjectNode().put("reason", "IT W-10 收口解绑"));
        assertThat(unbound.getStatusCode().is2xxSuccessful())
                .as("合法解绑应 2xx，实况：%s", unbound.getBody())
                .isTrue();

        // 数据库读操作：双 CAS 终局库态（UNBINDING 中间态不可停留，同事务两步迁移至 UNBOUND）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, unbind_reason FROM iot.iot_binding WHERE device_id = ? AND status = 'UNBOUND'"
                        + " ORDER BY id DESC LIMIT 1",
                DEVICE_ID);
        assertThat(row.get("status")).as("双 CAS 终态 UNBOUND").isEqualTo("UNBOUND");
        assertThat(row.get("unbind_reason")).as("解绑原因留痕").isEqualTo("IT W-10 收口解绑");

        awaitCaptured(2, "UNBIND 帧");
        JsonNode payload = BINDING_CHANGED.get(1).payload();
        assertThat(payload.path("changeType").asText()).as("事件语义 = UNBIND").isEqualTo("UNBIND");
        assertThat(payload.path("deviceId").asText()).isEqualTo(DEVICE_ID);
        assertThat(payload.path("patientId").isNull()
                        || payload.path("patientId").isMissingNode())
                .as("解绑载荷患者按契约置 null")
                .isTrue();
        assertThat(payload.path("visitId").isNull() || payload.path("visitId").isMissingNode())
                .as("解绑载荷就诊按契约置 null")
                .isTrue();

        // 重复解绑：无 BOUND 行 → 409 IOT-1010
        ResponseEntity<String> reUnbind = postForEntity(
                token,
                "/api/v1/iot/bindings/" + DEVICE_ID + "/unbind",
                objectMapper.createObjectNode().put("reason", "IT 重复解绑"));
        assertThat(reUnbind.getStatusCode().value()).as("重复解绑应 409").isEqualTo(409);
        assertThat(toNode(reUnbind.getBody()).path("errorCode").asText()).isEqualTo("IOT-1010");

        // 解绑后可再绑：BOUND 部分唯一索引释放，同患者再绑成功（双 CAS 闭环的业务出口）
        JsonNode rebound =
                postJson("/api/v1/iot/bindings", bearer(token), bindBody(DEVICE_ID, NORMAL_PATIENT_ID, boundVisitId));
        assertThat(rebound.path("status").asText()).as("解绑后再绑 BOUND").isEqualTo("BOUND");
        assertThat(rebound.path("visitId").asText()).isEqualTo(boundVisitId);
    }

    // ---------------------------------------------------------------- 种子与断言助手

    /**
     * 轮询等待护理投影在册行落库且床号补齐（admitted/bed.changed 两路消费收敛）——bind 校验链
     * 第四环在途就诊判定的直接前提，超时即失败（事件链断裂显式暴露，禁静默降级断言）。
     */
    private void awaitWardPatientRow() {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer ready = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM nursing.nursing_ward_patient"
                            + " WHERE visit_id = ? AND deleted = 0 AND bed_no IS NOT NULL AND bed_no <> ''",
                    Integer.class,
                    boundVisitId);
            if (ready != null && ready > 0) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("护理投影在册行未落库（visitId=" + boundVisitId + "）");
    }

    /** 设备种子直插（audit 列走库端默认值）。 */
    private void insertDevice(String deviceId, DeviceStatus status) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(deviceId);
        device.setDeviceName("IT W-10 种子设备");
        device.setDeviceType("monitor");
        device.setAccessMode(DeviceAccessMode.A);
        device.setWardId(WARD_ID);
        device.setStatus(status);
        assertThat(deviceMapper.insert(device)).as("设备种子插入成功：" + deviceId).isEqualTo(1);
    }

    /** information_schema 列类型实查：指定表列必须为 character varying 且长度 14（V1006 落位证据）。 */
    private void assertVarchar14(String table, String column) {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = 'iot' AND table_name = ? AND column_name = ?",
                table,
                column);
        assertThat(col.get("data_type"))
                .as(table + "." + column + " 类型应已改造为变长字符串")
                .isEqualTo("character varying");
        assertThat(((Number) col.get("character_maximum_length")).intValue())
                .as(table + "." + column + " 长度应为 CF-3 定长 14")
                .isEqualTo(CF3_VISIT_ID_LENGTH);
    }

    /** 绑定请求体（wardId/bindType 固定形态：FIXED + 病区 1001）。 */
    private ObjectNode bindBody(String deviceId, long patientId, String visitId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("deviceId", deviceId)
                .put("patientId", patientId)
                .put("visitId", visitId)
                .put("bedId", 2001L)
                .put("wardId", WARD_ID)
                .put("bindType", "FIXED")
                .put("bindReason", "IT W-10 绑定链");
        return body;
    }

    /** Bearer JSON POST（返回原始响应供状态码与 errorCode 双断言）。 */
    private ResponseEntity<String> postForEntity(String token, String path, ObjectNode body) {
        return restTemplate.postForEntity(path, new HttpEntity<>(body, bearer(token)), String.class);
    }

    /** Bearer 请求头构造。 */
    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    /** 无体响应解析兜底（204/空体回 MISSING 单例，既有 IT 同型收口）。 */
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

    /** 等待捕获列表达到目标帧数（MQ 异步消费的确定性等待）。 */
    private static void awaitCaptured(int expectedSize, String description) {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && BINDING_CHANGED.size() < expectedSize) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(BINDING_CHANGED.size()).as(description + "收帧达标").isGreaterThanOrEqualTo(expectedSize);
    }
}

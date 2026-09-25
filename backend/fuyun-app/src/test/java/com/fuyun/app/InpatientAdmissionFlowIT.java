package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.patient.api.VisitIdValidator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-1 验收锚点①：住院入院链真栈（HTTP/MQ/DB 零 mock）——住院证登记（WAITING）→预约入院
 * （床位 FREE→RESERVED 预占联动）→登记确认（同事务签发 I 型 14 位 visit_id + VisitIdValidator
 * 结构自检）→入科确认（床位 OCCUPIED + bed_assign 未闭合行开账）。
 *
 * <p>W-34 触发核验：inpatient.visit.registered（V901 id 65）/inpatient.visit.admitted（V800
 * id 48）/inpatient.bed.changed（V800 id 52）三事件经 fy.topic 真实投递，手工捕获队列收信后
 * 断言信封 eventType 与载荷关键字段（生产发布器链路，非注入帧）。
 *
 * <p>EMPI 合并拦截：M02 合并审批前置经 OngoingVisitQuery SPI 收集双实现（nursing + inpatient），
 * 在院就诊任一命中即阻断——调 M02 合并审批 API 断言 409 PAT-1006。
 *
 * <p>容器三件套类级独占（GC9 红线：容器禁收敛入基类，OutpatientFullFlowIT :61-77 同型；
 * @DynamicPropertySource 密钥三元组由 FuyunStackITBase 承载）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InpatientAdmissionFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（TimescaleDB/Redis/RabbitMQ tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 入院流程患者（住院证/就诊主体；EMPI 拦截面充当从档） */
    private static final long PATIENT_A = 920101L;

    /** EMPI 合并主档（无在院就诊；合并审批因从档 A 在院被 PAT-1006 阻断） */
    private static final long PATIENT_B = 920102L;

    /** 本 IT 病区与床位（SQL 直插——V903 bed 表零种子行，IT 自备主数据） */
    private static final String WARD_ID = "W-IT-9001";

    private static final long BED_ID = 920111L;

    private static final String BED_NO = "IT9-01";

    /** CF-3 当日首位 I 型 visit_id 冻结形态（容器独占 Redis 流水键自 1 起签发；与生成器同取默认时区日期） */
    private static final String EXPECTED_VISIT_ID =
            "I" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "00001";

    /** MQ 链路等待上限：覆盖 AFTER_COMMIT 发布与捕获队列投递（既有 IT 同款 10s 量级） */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(10);

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String admissionNo = "";

    private static String visitId = "";

    /**
     * 捕获队列声明（A.5-4：经治理构件声明，OutpatientFullFlowIT ItCaptureConfig 同型）。
     * 一事件一队列：q.it.inpatient.visit.registered / q.it.inpatient.visit.admitted /
     * q.it.inpatient.bed.changed（V901 id 65 与 V800 id 48/52 登记行）；声明副作用把 "it"
     * 追加进 subscriber_modules（不改登记行，与门诊先例同款）；raw 解析监听不进幂等台账。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_REGISTERED =
                MessagingConstants.QUEUE_PREFIX + "it." + InpatientMessagingConstants.EVENT_VISIT_REGISTERED;

        static final String Q_ADMITTED =
                MessagingConstants.QUEUE_PREFIX + "it." + InpatientMessagingConstants.EVENT_VISIT_ADMITTED;

        static final String Q_BED_CHANGED =
                MessagingConstants.QUEUE_PREFIX + "it." + InpatientMessagingConstants.EVENT_BED_CHANGED;

        static final CountDownLatch REGISTERED_LATCH = new CountDownLatch(1);

        static final CountDownLatch ADMITTED_LATCH = new CountDownLatch(1);

        static final CountDownLatch BED_CHANGED_LATCH = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itRegisteredCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", InpatientMessagingConstants.EVENT_VISIT_REGISTERED));
        }

        @Bean
        Declarables itAdmittedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", InpatientMessagingConstants.EVENT_VISIT_ADMITTED));
        }

        @Bean
        Declarables itBedChangedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", InpatientMessagingConstants.EVENT_BED_CHANGED));
        }

        @Bean
        ItCaptureListener itCaptureListener(EventEnvelopeCodec codec) {
            return new ItCaptureListener(codec);
        }
    }

    /** 监听器本体（测试侧轻量：解析→按事件类型置闩，不登记 received_event）。 */
    static class ItCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(
                queues = {ItCaptureConfig.Q_REGISTERED, ItCaptureConfig.Q_ADMITTED, ItCaptureConfig.Q_BED_CHANGED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            if (InpatientMessagingConstants.EVENT_VISIT_REGISTERED.equals(envelope.eventType())) {
                ItCaptureConfig.REGISTERED_LATCH.countDown();
            } else if (InpatientMessagingConstants.EVENT_VISIT_ADMITTED.equals(envelope.eventType())) {
                ItCaptureConfig.ADMITTED_LATCH.countDown();
            } else if (InpatientMessagingConstants.EVENT_BED_CHANGED.equals(envelope.eventType())) {
                ItCaptureConfig.BED_CHANGED_LATCH.countDown();
            }
        }
    }

    /** 带 Bearer 的 GET 助手（OutpatientFullFlowIT :187-194 同型）。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

    private JsonNode toNode(String body) {
        // 无体响应（void/204 端点）回 MISSING 单例，防 readTree 拒 null 中断用例（既有 IT 同型收口）
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体 errorCode 断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 读床位行实态（SQL 直读——床位五态与占用主体断言锚）。 */
    private Map<String, Object> bedRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, visit_id, ward_id, bed_no FROM inpatient.bed WHERE id = ?", BED_ID);
    }

    @Test
    @Order(1)
    @DisplayName("前置：admin/reviewer 登录；患者两档+病区床位一行直插（V903 零种子，IT 自备主数据）")
    void prepareMasterData() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_A,
                "入院链患者");
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_B,
                "合并主档患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
                BED_NO,
                WARD_ID);
    }

    @Test
    @Order(2)
    @DisplayName("住院证登记：POST /admissions 建单即入 WAITING 候床队列，AD 号契约 AD+yyyyMMdd+5 位")
    void createAdmissionEntersWaitingQueue() {
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_A)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_ID)
                .put("diagnosisSummary", "IT 验收：入院链直线段")
                .put("issuedDoctorId", "3");
        JsonNode vo = toNode(postForEntity("/api/v1/inpatient/admissions", adminToken, create)
                .getBody());
        admissionNo = vo.path("admissionNo").asText();
        assertThat(admissionNo).as("住院证号契约 AD+yyyyMMdd+5 位流水").matches("AD\\d{13}");
        assertThat(vo.path("status").asText()).as("登记即建单入候床队列").isEqualTo("WAITING");

        // 候床队列读面：WAITING 过滤可查得本单（队列语义闭合）
        JsonNode queue = getJson("/api/v1/inpatient/admissions?status=WAITING&page=0&size=10", adminToken);
        assertThat(queue.path("content").toString()).as("候床队列应含本住院证").contains(admissionNo);
    }

    @Test
    @Order(3)
    @DisplayName("预约入院：WAITING→SCHEDULED 且携床位预约同事务联动预占（床位 FREE→RESERVED + bed.changed 帧在位）")
    void scheduleAdmissionReservesBed() throws Exception {
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_ID)
                .put("targetBedId", BED_ID)
                .put("expectDate", LocalDate.now().toString());
        JsonNode vo =
                toNode(postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule)
                        .getBody());
        assertThat(vo.path("status").asText()).as("预约后证状态").isEqualTo("SCHEDULED");
        assertThat(vo.path("targetBedId").asLong()).as("预约目标床位回显").isEqualTo(BED_ID);

        // 床位预占联动（schedule 同事务 reserveForAdmission）：RESERVED 且未绑定就诊主体（登记确认才签发）
        Map<String, Object> bed = bedRow();
        assertThat(bed.get("status")).as("预约入院应同事务预占床位").isEqualTo("RESERVED");
        assertThat(bed.get("visit_id"))
                .as("预占不绑定 visit_id（V903 冗余列权威在 bed_assign）")
                .isNull();

        // bed.changed 真实投递帧在位：FREE→RESERVED 迁移帧（V800 id 52 载荷面）
        assertThat(ItCaptureConfig.BED_CHANGED_LATCH.await(LINK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("inpatient.bed.changed 事件应可消费")
                .isTrue();
        EventEnvelope changed = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> InpatientMessagingConstants.EVENT_BED_CHANGED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(changed.producer()).isEqualTo(InpatientMessagingConstants.MODULE);
        assertThat(changed.payload().path("wardId").asText()).isEqualTo(WARD_ID);
        assertThat(changed.payload().path("bedId").asLong()).isEqualTo(BED_ID);
        assertThat(changed.payload().path("bedNo").asText()).isEqualTo(BED_NO);
        assertThat(changed.payload().path("bedStatus").asText()).isEqualTo("RESERVED");
        assertThat(changed.payload().hasNonNull("patientId"))
                .as("预占无主体场景 patientId 为 null（载荷契约）")
                .isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("登记确认：同事务签发 I 型 14 位 visit_id（当日首位 I+yyyyMMdd+00001）+ VisitIdValidator 自检 + registered 帧在位")
    void registerAdmissionIssuesVisitId() throws Exception {
        ObjectNode register = objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO");
        JsonNode vo =
                toNode(postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/register", adminToken, register)
                        .getBody());
        visitId = vo.path("visitId").asText();
        assertThat(visitId).as("CF-3 当日首位 I 型 visit_id 冻结形态").isEqualTo(EXPECTED_VISIT_ID);
        assertThat(visitId).as("M02 结构规范：定长 14 位").hasSize(14);
        assertThat(VisitIdValidator.isValid(visitId))
                .as("VisitIdValidator 结构自检应通过")
                .isTrue();
        assertThat(vo.path("status").asText()).as("登记确认后就诊态").isEqualTo("REGISTERED");
        assertThat(vo.path("insuranceType").asText()).isEqualTo("IT-YIBAO");

        // 证状态终态：COMPLETED（register CAS WAITING/SCHEDULED→COMPLETED）
        String admissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM inpatient.admission WHERE admission_no = ?", String.class, admissionNo);
        assertThat(admissionStatus).as("登记确认后住院证置终态").isEqualTo("COMPLETED");

        // registered 真实投递帧在位（V901 id 65 契约面：五组件勾稽）
        assertThat(ItCaptureConfig.REGISTERED_LATCH.await(LINK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("inpatient.visit.registered 事件应可消费")
                .isTrue();
        EventEnvelope registered = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> InpatientMessagingConstants.EVENT_VISIT_REGISTERED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(registered.producer()).isEqualTo(InpatientMessagingConstants.MODULE);
        assertThat(registered.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(registered.payload().path("patientId").asLong()).isEqualTo(PATIENT_A);
        assertThat(registered.payload().path("admissionNo").asText()).isEqualTo(admissionNo);
        assertThat(registered.payload().path("insuranceType").asText()).isEqualTo("IT-YIBAO");
        assertThat(registered.payload().hasNonNull("registeredAt"))
                .as("登记时点组件非空（Instant UTC）")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("入科确认：REGISTERED→ADMITTED + 床位 OCCUPIED + bed_assign 未闭合行 + admitted 帧载荷六组件勾稽")
    void admitWardOccupiesBedWithOpenAssign() throws Exception {
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("deptId", "DEP-IT-9001")
                .put("wardId", WARD_ID)
                .put("bedId", BED_ID)
                .put("nursingLevel", "NORMAL")
                .put("attendingDoctorId", "3");
        JsonNode vo = toNode(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                .getBody());
        assertThat(vo.path("status").asText()).as("入科确认后在院态").isEqualTo("ADMITTED");
        assertThat(vo.path("currentWardId").asText()).isEqualTo(WARD_ID);
        assertThat(vo.path("currentBedId").asLong()).isEqualTo(BED_ID);

        // 床位 OCCUPIED 且冗余列绑定就诊；占用流水恰好一条未闭合行（uk_bed_assign_open 权威面）
        Map<String, Object> bed = bedRow();
        assertThat(bed.get("status")).as("入科确认应占床").isEqualTo("OCCUPIED");
        assertThat(bed.get("visit_id")).isEqualTo(visitId);
        Integer openAssignRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.bed_assign WHERE bed_id = ? AND visit_id = ?"
                        + " AND ended_at IS NULL AND deleted = 0",
                Integer.class,
                BED_ID,
                visitId);
        assertThat(openAssignRows).as("入科应开账恰好一条未闭合占用流水").isEqualTo(1);

        // admitted 真实投递帧在位（V800 id 48 载荷六组件勾稽）
        assertThat(ItCaptureConfig.ADMITTED_LATCH.await(LINK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("inpatient.visit.admitted 事件应可消费")
                .isTrue();
        EventEnvelope admitted = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> InpatientMessagingConstants.EVENT_VISIT_ADMITTED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(admitted.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(admitted.payload().path("patientId").asLong()).isEqualTo(PATIENT_A);
        assertThat(admitted.payload().path("wardId").asText()).isEqualTo(WARD_ID);
        assertThat(admitted.payload().path("bedId").asLong()).isEqualTo(BED_ID);
        assertThat(admitted.payload().path("nursingLevel").asText()).isEqualTo("NORMAL");
        assertThat(admitted.payload().hasNonNull("admittedAt")).isTrue();

        // bed.changed 占床帧在位：OCCUPIED 迁移帧携占用主体（与预约帧同队列区分按状态过滤）
        EventEnvelope occupied = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> InpatientMessagingConstants.EVENT_BED_CHANGED.equals(e.eventType()))
                .filter(e -> "OCCUPIED".equals(e.payload().path("bedStatus").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(occupied.payload().path("patientId").asLong())
                .as("占床帧携占用患者主索引")
                .isEqualTo(PATIENT_A);
    }

    @Test
    @Order(6)
    @DisplayName("EMPI 合并拦截：从档存在在院住院就诊，M02 合并审批前置经 OngoingVisitQuery 双实现阻断 409 PAT-1006")
    void mergeApproveBlockedByOngoingInpatientVisit() {
        // 发起合并（经办=admin）：主档 B ← 从档 A（A 在院）
        ObjectNode merge = objectMapper.createObjectNode();
        merge.put("survivorPatientId", PATIENT_B)
                .put("mergedPatientId", PATIENT_A)
                .put("mergeReason", "IT 验收：在院就诊合并拦截");
        JsonNode created = toNode(
                postForEntity("/api/v1/patient/merges", adminToken, merge).getBody());
        long mergeId = created.path("id").asLong();

        // 审批（审批人=reviewer，双人角色成立）：在院前置检查命中 inpatient SPI → PAT-1006
        ResponseEntity<String> rejected = postForEntity(
                "/api/v1/patient/merges/" + mergeId + "/approve", reviewerToken, objectMapper.createObjectNode());
        assertThat(rejected.getStatusCode().value()).as("在院就诊合并审批应被阻断 409").isEqualTo(409);
        assertThat(toNode(rejected.getBody()).path("errorCode").asText())
                .as("阻断错误码 PAT-1006（MERGE_BLOCKED_BY_ONGOING_VISIT）")
                .isEqualTo("PAT-1006");

        // 阻断不落执行：从档 A 档案状态保持 NORMAL 未被并档（SPI 检查先于合并执行）
        String mergedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM patient.patient WHERE patient_id = ?", String.class, PATIENT_A);
        assertThat(mergedStatus).as("阻断后从档档案状态不变").isEqualTo("NORMAL");
    }
}

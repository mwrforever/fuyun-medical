package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.integration.constants.MessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
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
 * PR-1 验收锚点②（CF-6 验收核心）：住院医嘱闭环全链真栈（HTTP/MQ/DB 零 mock）——
 * 开立用药医嘱（CREATED，order.created.drug 子键投递）→ pharmacy 消费落审方任务 →
 * 药师通过（回执驱动 CREATED→AUDITED，audited.drug 投递）→ 双人转抄核对（TRANSFERRED +
 * 临时单次计划 [+默认准备窗口 60 分钟]）→ CF-6 执行回签（计划 EXECUTED + 医嘱 COMPLETED +
 * executed 投递 + 响应四字段与 V901 id 55 契约逐字）→ 停嘱路径（第二条医嘱 stopped →
 * PENDING 计划全 CANCELLED）；另：lab 医嘱自动过审（audited.lab 子键路由）+ M13 通配消费
 * （billing 经自声明 q.billing.inpatient.order.# 通配队列真实消费——开单计价 PENDING 落行
 * 与回签 CONFIRMED 的 DB 终态为投递证据）。
 *
 * <p>捕获形态：住院医嘱族事件 routing key 携类型子键（R3-06），governance 订阅登记按登记名
 * 精确匹配无法绑定通配段（BillingMessagingConfig 生产侧自声明同款先例）——本 IT 按治理同款
 * 形态自声明 q.it.inpatient.order.#（durable quorum + fy.dlx 死信 + 绑定 fy.topic），事件
 * 本体均已登记（V800 id 41-47/V901 id 66，先登记后订阅红线满足）；raw 解析监听不进幂等台账。
 *
 * <p>容器三件套类级独占（GC9 红线，InpatientAdmissionFlowIT 同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InpatientOrderFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
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

    /** 开单项目码（billing API 建项+定价：药品 2000 分/检验 3000 分——计价引擎种子价取数面） */
    private static final String DRUG_ITEM_CODE = "IT-DRUG-001";

    private static final String LAB_ITEM_CODE = "IT-LAB-001";

    /** 本 IT 病区与床位（SQL 直插主数据） */
    private static final String WARD_ID = "W-IT-9002";

    private static final long BED_ID = 920211L;

    private static final long PATIENT_ID = 920201L;

    /** V704 演示医师（sys_user id=3 持 PRESCRIPTION 执业授权——开单操作者与开立校验 L1 主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** CF-3 当日首位 I 型 visit_id 冻结形态（容器独占 Redis 流水键自 1 起签发） */
    private static final String EXPECTED_VISIT_ID =
            "I" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "00001";

    /** MQ 链路与消费面等待上限（回执驱动迁移/计费行为异步收敛，10s 量级与既有 IT 同款） */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(10);

    /** DB 轮询步进（回执消费/计费行落库 100ms 步进轮询足够收敛） */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** RabbitAdmin：超时诊断读取 billing 通配队列声明态 */
    @Autowired
    private AmqpAdmin amqpAdmin;

    /** 跨用例链路状态 */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String drugOrderNo = "";

    private static String stopOrderNo = "";

    private static String labOrderNo = "";

    /**
     * 住院医嘱族通配捕获队列（自声明——governance 无法承载子键通配段，BillingMessagingConfig
     * :84-105 生产侧同款先例；队列名 q.it.inpatient.order.# 按模块.绑定键推导）。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String CAPTURE_QUEUE = "q.it.inpatient.order.#";

        static final CountDownLatch FIRST_CREATED_DRUG = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itInpatientOrderWildcardQueue() {
            Queue queue = QueueBuilder.durable(CAPTURE_QUEUE)
                    .quorum()
                    .deadLetterExchange("fy.dlx")
                    .build();
            Binding binding = new Binding(
                    CAPTURE_QUEUE,
                    Binding.DestinationType.QUEUE,
                    MessagingConstants.EXCHANGE_TOPIC,
                    "inpatient.order.#",
                    null);
            return new Declarables(queue, binding);
        }

        @Bean
        ItCaptureListener itCaptureListener(EventEnvelopeCodec codec) {
            return new ItCaptureListener(codec);
        }
    }

    /** 监听器本体（测试侧轻量：解析→置闩，不登记 received_event）。 */
    static class ItCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = ItCaptureConfig.CAPTURE_QUEUE)
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            // 投递面 eventType 携类型子键（R3-06）：drug 子键帧=登记名+".drug"
            if ((InpatientMessagingConstants.EVENT_ORDER_CREATED + ".drug").equals(envelope.eventType())) {
                ItCaptureConfig.FIRST_CREATED_DRUG.countDown();
            }
        }
    }

    /** 带 Bearer 的 GET 助手。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

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

    /** 带令牌 POST（返回原始响应实体，状态码与体断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 建项目+定价+发布一步到位（OutpatientFullFlowIT newItemWithPrice :229-256 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 医嘱链项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        long itemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("itemCode", itemCode)
                .put("price", priceFen)
                .put("effectiveFrom", OffsetDateTime.now().toString())
                .put("priceSource", "OFFICIAL_DOC");
        long priceRowId = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + priceRowId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
    }

    /** 开立一条医嘱（doctordemo 持权单行明细）。 */
    private JsonNode createOrder(String orderType, String orderClass, String freqCode, String itemCode) {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", orderType).put("orderClass", orderClass);
        if (freqCode != null) {
            order.put("freqCode", freqCode);
        }
        ObjectNode line = order.putArray("items").addObject();
        line.put("itemType", orderType)
                .put("itemCode", itemCode)
                .put("itemName", "IT 项目 " + itemCode)
                .put("quantity", "1");
        if ("DRUG".equals(orderType)) {
            // 药品行三要素必填（服务层 IP-1011 校验面）
            line.put("dosage", "0.5").put("dosageUnit", "g").put("route", "ORAL");
        }
        return postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order);
    }

    /** 药师通过指定医嘱的待审任务（工作台列表轮询取任务 id → approve；任务落库为消费异步面禁盲取）。 */
    private void approveReviewTask(String orderNo) throws InterruptedException {
        String taskId = null;
        for (int i = 0; i < 100; i++) {
            JsonNode list = getJson("/api/v1/pharmacy/review-tasks?status=PENDING&page=0&size=50", adminToken);
            for (JsonNode row : list.path("content")) {
                if (orderNo.equals(row.path("m04OrderNo").asText())) {
                    taskId = row.path("id").asText();
                    break;
                }
            }
            if (taskId != null) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(taskId).as("医嘱 %s 的待审任务应在工作台", orderNo).isNotNull();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/review-tasks/" + taskId + "/approve",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }

    /** 转抄核对放行（双人签名：转抄护士 + 第二核对人）。 */
    private void transferCheck(String orderNo) {
        ObjectNode check = objectMapper.createObjectNode();
        check.putArray("orderNos").add(orderNo);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED").put("secondCheckerId", "IT-NURSE-2");
        assertThat(postForEntity("/api/v1/inpatient/orders/transfer-check", adminToken, check)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }

    /** 读医嘱当前状态（SQL 直读）。 */
    private String orderStatus(String orderNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM inpatient.medical_order WHERE order_no = ?", String.class, orderNoText);
    }

    /** 轮询等待医嘱到达目标状态（回执驱动异步收敛，禁盲等）。 */
    private void awaitOrderStatus(String orderNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(orderStatus(orderNoText))) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("医嘱状态迁移超时：orderNo=" + orderNoText + "，期望 " + expected);
    }

    /** 按事件类型与类型子键过滤捕获帧（子键帧 eventType=登记名+ "." + 子键）。 */
    private EventEnvelope captured(String eventTypePrefix) {
        return ItCaptureConfig.CAPTURED.stream()
                .filter(e -> e.eventType().startsWith(eventTypePrefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("捕获队列未收到帧：" + eventTypePrefix));
    }

    @Test
    @Order(1)
    @DisplayName("前置：三账号登录；两项目建项定价发布；患者+床位直插；入院链四步至 ADMITTED")
    void prepareStackAndAdmitPatient() throws Exception {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        newItemWithPrice(DRUG_ITEM_CODE, 2000);
        newItemWithPrice(LAB_ITEM_CODE, 3000);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "医嘱链患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
                "IT9-02",
                WARD_ID);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_ID)
                .put("issuedDoctorId", "3");
        String admissionNo = postJson("/api/v1/inpatient/admissions", adminToken, create)
                .path("admissionNo")
                .asText();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_ID)
                .put("targetBedId", BED_ID)
                .put("expectDate", LocalDate.now().toString());
        postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule);
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        assertThat(visitId).as("入院链签发 visit_id 冻结形态").isEqualTo(EXPECTED_VISIT_ID);
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_ID).put("bedId", BED_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("开立用药医嘱：CREATED + order.created.drug 子键帧投递（载荷 m04OrderNo/items/频次缺省面勾稽）")
    void createDrugOrderPublishesSubKeyFrame() throws Exception {
        JsonNode vo = createOrder("DRUG", "STAT", null, DRUG_ITEM_CODE);
        drugOrderNo = vo.path("orderNo").asText();
        assertThat(drugOrderNo).as("医嘱号契约 MO+yyyyMMdd+5 位流水").matches("MO\\d{13}");
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");
        assertThat(vo.path("groupNo").asText()).as("单条医嘱组号缺省回填 order_no").isEqualTo(drugOrderNo);

        // order.created.drug 真实投递帧在位（V901 id 66 载荷面，routing key 携 drug 子键）
        assertThat(ItCaptureConfig.FIRST_CREATED_DRUG.await(LINK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("order.created.drug 帧应可消费")
                .isTrue();
        EventEnvelope created = captured(InpatientMessagingConstants.EVENT_ORDER_CREATED + ".drug");
        assertThat(created.eventType())
                .as("R3-06：投递面 eventType 携 drug 子键（登记名 id 66 不带子键）")
                .isEqualTo(InpatientMessagingConstants.EVENT_ORDER_CREATED + ".drug");
        assertThat(created.payload().path("m04OrderNo").asText()).isEqualTo(drugOrderNo);
        assertThat(created.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(created.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(created.payload().path("orderType").asText()).isEqualTo("drug");
        assertThat(created.payload().path("orderClass").asText()).isEqualTo("STAT");
        assertThat(created.payload().path("items").get(0).path("itemCode").asText())
                .isEqualTo(DRUG_ITEM_CODE);

        // M13 通配消费证据①：billing 消费 created 帧离散计价——PENDING 费用行落库（2000 分×1）
        awaitFeeRow(drugOrderNo, "PENDING", 2000L);
    }

    /** 轮询等待该医嘱费用行到达目标状态与金额（billing 通配消费异步收敛；超时附死信诊断面）。 */
    private Map<String, Object> awaitFeeRow(String orderNoText, String status, Long amount)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, amount FROM billing.fee_record WHERE source_ref = ? AND visit_id = ?"
                            + " AND deleted = 0",
                    orderNoText,
                    visitId);
            for (Map<String, Object> row : rows) {
                if (status.equals(row.get("status")) && (amount == null || amount.equals(row.get("amount")))) {
                    return row;
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        // 超时诊断：死信台账 + billing 消费幂等台账 + 通配队列声明态（定位消费链断点）
        List<Map<String, Object>> deadLetters =
                jdbcTemplate.queryForList("SELECT event_type, fail_reason FROM integration.dead_letter"
                        + " WHERE event_type LIKE 'inpatient.order%' ORDER BY created_at DESC LIMIT 5");
        List<Map<String, Object>> billingReceived =
                jdbcTemplate.queryForList("SELECT event_type, event_id FROM integration.received_event"
                        + " WHERE consumer_module = 'billing' ORDER BY id DESC LIMIT 5");
        Object queueDeclared = amqpAdmin.getQueueProperties("q.billing.inpatient.order.#");
        throw new IllegalStateException("费用行未到位：orderNo="
                + orderNoText
                + "，期望 "
                + status
                + "/"
                + amount
                + "，死信近帧="
                + deadLetters
                + "，billing 已消费近帧="
                + billingReceived
                + "，billing 通配队列声明态="
                + queueDeclared
                + "，费用行全集="
                + jdbcTemplate.queryForList(
                        "SELECT visit_id, item_name_snapshot, source_ref, trigger_point, status, amount"
                                + " FROM billing.fee_record LIMIT 10"));
    }

    @Test
    @Order(3)
    @DisplayName("药师审方通过：pharmacy 消费 created.drug 落 PENDING 任务→approve 回执→医嘱 AUDITED + audited.drug 帧")
    void pharmacistApprovalDrivesAudited() throws Exception {
        // pharmacy 消费落审方任务（轮询等待消费收敛）
        Integer pendingTask = null;
        for (int i = 0; i < 100; i++) {
            pendingTask = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pharmacy.review_task WHERE deleted = 0 AND status = 'PENDING'",
                    Integer.class);
            if (pendingTask != null && pendingTask >= 1) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(pendingTask).as("pharmacy 应回执前落 PENDING 审方任务").isNotNull().isGreaterThanOrEqualTo(1);

        approveReviewTask(drugOrderNo);
        awaitOrderStatus(drugOrderNo, "AUDITED");

        // audited.drug 真实投递帧在位（V800 id 41 载荷面：药师路径审计面勾稽）
        EventEnvelope audited = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_AUDITED + ".drug");
        assertThat(audited.payload().path("m04OrderNo").asText()).isEqualTo(drugOrderNo);
        assertThat(audited.payload().path("auditType").asText()).as("药师审方路径").isEqualTo("PHARMACIST");
        assertThat(audited.payload().path("auditOperator").asText())
                .as("审方操作者=reviewer userId")
                .isEqualTo(String.valueOf(REVIEWER_USER_ID));
        assertThat(audited.payload().path("visitId").asText()).isEqualTo(visitId);
    }

    /** 轮询等待捕获队列出现指定前缀帧（AFTER_COMMIT 出 MQ 异步收敛）。 */
    private EventEnvelope awaitCaptured(String eventTypePrefix) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<EventEnvelope> matched = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> e.eventType().startsWith(eventTypePrefix))
                    .toList();
            if (!matched.isEmpty()) {
                return matched.get(0);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventTypePrefix);
    }

    @Test
    @Order(4)
    @DisplayName("双人转抄核对：AUDITED→TRANSFERRED + 转抄台账行 + 临时单次计划 PENDING（+60 分钟准备窗口）")
    void transferCheckCreatesSinglePlan() throws Exception {
        OffsetDateTime before = OffsetDateTime.now();
        transferCheck(drugOrderNo);
        awaitOrderStatus(drugOrderNo, "TRANSFERRED");

        // 转抄台账行（双人核对留痕：转抄护士/第二核对人；台账以 order_id 关联医嘱）
        Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT t.transfer_nurse, t.second_checker_id, t.conclusion FROM inpatient.order_transfer_log t"
                        + " JOIN inpatient.medical_order o ON o.id = t.order_id"
                        + " WHERE o.order_no = ? AND t.deleted = 0",
                drugOrderNo);
        assertThat(logRow.get("transfer_nurse")).isEqualTo("IT-NURSE-1");
        assertThat(logRow.get("second_checker_id")).isEqualTo("IT-NURSE-2");
        assertThat(logRow.get("conclusion")).isEqualTo("PASSED");

        // 临时单次计划（STAT 转抄同步生成）：PENDING 且 plan_time=转抄时点+默认准备窗口 60 分钟
        Map<String, Object> plan = jdbcTemplate.queryForMap(
                "SELECT plan_no, status, ward_id, plan_time FROM inpatient.order_execute_plan"
                        + " WHERE order_id = (SELECT id FROM inpatient.medical_order WHERE order_no = ?)"
                        + " AND deleted = 0",
                drugOrderNo);
        assertThat(plan.get("status")).as("临时单次计划初始待执行").isEqualTo("PENDING");
        assertThat(plan.get("ward_id")).as("计划病区=生成时点患者所在病区").isEqualTo(WARD_ID);
        java.sql.Timestamp planTime = (java.sql.Timestamp) plan.get("plan_time");
        long minutes =
                Duration.between(before.toInstant(), planTime.toInstant()).toMinutes();
        assertThat(minutes).as("单次计划时点=转抄时点+60 分钟准备窗口（InpatientProperties 默认）").isBetween(55L, 70L);
    }

    @Test
    @Order(5)
    @DisplayName("CF-6 执行回签：计划 EXECUTED + 医嘱 COMPLETED + 响应恰四字段（V901 id 55 契约）+ executed 帧 + 费用 CONFIRMED")
    void executeConfirmMatchesId55Contract() throws Exception {
        String planNo = jdbcTemplate.queryForObject(
                "SELECT plan_no FROM inpatient.order_execute_plan"
                        + " WHERE order_id = (SELECT id FROM inpatient.medical_order WHERE order_no = ?)"
                        + " AND deleted = 0",
                String.class,
                drugOrderNo);
        assertThat(planNo).as("计划号契约 PL+yyyyMMdd+5 位流水").matches("PL\\d{13}");

        JsonNode vo = postJson(
                "/api/v1/inpatient/order-plans/" + planNo + "/execute-confirm",
                adminToken,
                objectMapper.createObjectNode().put("executorId", 66).put("routeCheckResult", "IT 口服核对无误"));
        // W-33 id 55 字段级契约：响应恰四字段 planNo/m04OrderNo/orderStatus/planStatus
        assertThat(vo.size()).as("回签出参恰四字段（契约冻结面）").isEqualTo(4);
        assertThat(vo.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("planNo", "m04OrderNo", "orderStatus", "planStatus");
        assertThat(vo.path("planNo").asText()).isEqualTo(planNo);
        assertThat(vo.path("m04OrderNo").asText()).isEqualTo(drugOrderNo);
        assertThat(vo.path("orderStatus").asText()).as("临时医嘱单次回签→COMPLETED").isEqualTo("COMPLETED");
        assertThat(vo.path("planStatus").asText()).isEqualTo("EXECUTED");

        // 计划行值面：执行人/时点/途径核对结论落值（W-33 存储落点）
        Map<String, Object> plan = jdbcTemplate.queryForMap(
                "SELECT executor_id, executed_at, route_check_result FROM inpatient.order_execute_plan"
                        + " WHERE plan_no = ?",
                planNo);
        assertThat(plan.get("executor_id")).isEqualTo("66");
        assertThat(plan.get("executed_at")).isNotNull();
        assertThat(plan.get("route_check_result")).isEqualTo("IT 口服核对无误");

        // executed 真实投递帧在位（V800 id 47 载荷五组件勾稽）
        EventEnvelope executed = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_EXECUTED);
        assertThat(executed.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_EXECUTED);
        assertThat(executed.payload().path("planNo").asText()).isEqualTo(planNo);
        assertThat(executed.payload().path("m04OrderNo").asText()).isEqualTo(drugOrderNo);
        assertThat(executed.payload().path("visitId").asText()).isEqualTo(visitId);

        // M13 通配消费证据②：billing 消费 executed 帧——该医嘱费用行 PENDING→CONFIRMED
        awaitFeeRow(drugOrderNo, "CONFIRMED", 2000L);
    }

    @Test
    @Order(6)
    @DisplayName("停嘱路径：第二条医嘱经审方转抄后 STOPPED，PENDING 计划全 CANCELLED + stopped 帧（原因/操作者勾稽）")
    void stopOrderCancelsPendingPlans() throws Exception {
        JsonNode vo = createOrder("DRUG", "STAT", null, DRUG_ITEM_CODE);
        stopOrderNo = vo.path("orderNo").asText();
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");
        approveReviewTask(stopOrderNo);
        awaitOrderStatus(stopOrderNo, "AUDITED");
        transferCheck(stopOrderNo);
        awaitOrderStatus(stopOrderNo, "TRANSFERRED");

        // 医生停嘱（TRANSFERRED→STOPPED 合法边）
        assertThat(postForEntity(
                                "/api/v1/inpatient/orders/" + stopOrderNo + "/stop",
                                doctorToken,
                                objectMapper.createObjectNode().put("reason", "IT 验收：病情好转停嘱"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitOrderStatus(stopOrderNo, "STOPPED");

        // 停嘱联动：未来执行计划全量作废（PENDING→CANCELLED）
        Integer pendingPlans = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan"
                        + " WHERE order_id = (SELECT id FROM inpatient.medical_order WHERE order_no = ?)"
                        + " AND status = 'PENDING' AND deleted = 0",
                Integer.class,
                stopOrderNo);
        assertThat(pendingPlans).as("停嘱后 PENDING 计划应清零").isZero();
        Integer cancelledPlans = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan"
                        + " WHERE order_id = (SELECT id FROM inpatient.medical_order WHERE order_no = ?)"
                        + " AND status = 'CANCELLED' AND deleted = 0",
                Integer.class,
                stopOrderNo);
        assertThat(cancelledPlans).as("停嘱医嘱原单次计划应转 CANCELLED").isEqualTo(1);

        // stopped 真实投递帧在位（V800 id 44 载荷面：时点/操作者/原因勾稽）
        EventEnvelope stopped = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_STOPPED);
        assertThat(stopped.payload().path("m04OrderNo").asText()).isEqualTo(stopOrderNo);
        assertThat(stopped.payload().path("stopReason").asText()).isEqualTo("IT 验收：病情好转停嘱");
        assertThat(stopped.payload().path("stopOperator").asText())
                .as("停嘱操作者=医生 userId")
                .isEqualTo("3");
        assertThat(stopped.payload().hasNonNull("stoppedAt")).isTrue();

        // M13 截断联动（事件链完整性旁证）：该医嘱 PENDING 费用行作废
        awaitFeeRow(stopOrderNo, "CANCELLED", null);
    }

    @Test
    @Order(7)
    @DisplayName("lab 医嘱自动过审：免药师审方直入 AUDITED + audited.lab 子键路由 + 审方任务零误建（M06 仅绑 drug）")
    void labOrderAutoAuditsWithSubKeyRouting() throws Exception {
        JsonNode vo = createOrder("LAB", "STAT", null, LAB_ITEM_CODE);
        labOrderNo = vo.path("orderNo").asText();
        assertThat(vo.path("status").asText()).as("非用药类开立后系统自动过审").isEqualTo("AUDITED");

        // audited.lab 子键路由帧在位（SYSTEM 路径：审核操作者=开立医生）
        EventEnvelope audited = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_AUDITED + ".lab");
        assertThat(audited.eventType())
                .as("子键路由：帧 eventType 携 lab 子键（登记名不带子键 R3-06）")
                .isEqualTo(InpatientMessagingConstants.EVENT_ORDER_AUDITED + ".lab");
        assertThat(audited.payload().path("m04OrderNo").asText()).isEqualTo(labOrderNo);
        assertThat(audited.payload().path("auditType").asText()).isEqualTo("SYSTEM");
        assertThat(audited.payload().path("auditOperator").asText()).isEqualTo("3");

        // M06 薄切片自洽：lab 类不建审方任务（pharmacy 仅精确绑定 created.drug；任务经 order_medication 快照关联）
        Integer labTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.review_task t"
                        + " JOIN pharmacy.order_medication m ON m.id = t.order_medication_id"
                        + " WHERE m.m04_order_no = ? AND t.deleted = 0",
                Integer.class,
                labOrderNo);
        assertThat(labTasks).as("lab 类不得建审方任务").isZero();
    }
}

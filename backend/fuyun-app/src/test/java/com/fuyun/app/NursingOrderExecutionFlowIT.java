package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.service.IOrderPlanService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * PR-3 验收锚点①（五环节闭环全链——P2 阶段交付验证物，真栈 HTTP/MQ/DB 零 mock）：住院口服
 * 长期医嘱开立→pharmacy 审方通过→摆药计划生成→摆药五步（pick/verify[异人双签]/issue[库存三连
 * +调剂行]/deliver[时间线半步不迁状态]/receive[dispense.completed 发布]）→nursing 执行单批量
 * SIGNED→腕带三向核对→开始执行→执行完成→M04 计划回签 EXECUTED（主路径）+回执帧可观测（辅
 * 路径）+对账零差异锚（全单无 COMPENSATING 残留——回签主路径直达的 DB 表达）。
 *
 * <p><b>ledger 义务携带</b>：deliver 不迁状态（仅 issued_at 时间线半步，Task 8 裁决）——断言
 * deliver 后计划行保持 CHECKED、issued_at 非空；SIGNED 迁移归 receive 后的摆药签收衔接消费。
 *
 * <p>容器三件套类级独占（GC9 红线，InpatientOrderFlowIT :74-88 逐字同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NursingOrderExecutionFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（流水键/幂等前置键载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（医嘱/摆药/回执事件族消费链载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 口服链项目码与药品码共码（billing 建项定价 + pharmacy 药品建档对照） */
    private static final String ORAL_DRUG_CODE = "IT-ORAL-DRUG-001";

    /** 本 IT 病区与床位（SQL 直插主数据） */
    private static final String WARD_ID = "W-IT-9004";

    private static final long BED_ID = 920411L;

    private static final long PATIENT_ID = 920401L;

    /** V704 演示医师（开单操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** MQ 消费链路等待上限 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String orderNo = "";

    /** 摆药计划号（五步链载体） */
    private static String dispensePlanNo = "";

    /** 次日计划执行单号（check→start→finish 驱动载体） */
    private static String planExecutionNo = "";

    /** 驱动载体 M04 计划号（回签对账锚） */
    private static String m04PlanNo = "";

    /** 日切分解服务（次日计划预置——order-plan.generated 事件源） */
    @Autowired
    private IOrderPlanService orderPlanService;

    /**
     * 捕获队列声明（A.5-4 治理红线：经 MessagingGovernance 声明，"it" 消费者模块形态）：
     * dispense.completed（id 28）与 order-execution.completed（id 64）各一队列。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_DISPENSE_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED;

        static final String Q_EXECUTION_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED;

        static final List<EventEnvelope> DISPENSE_COMPLETED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> EXECUTION_COMPLETED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itOrderFlowCaptureQueues(MessagingGovernance governance) {
            List<Declarable> declared = new ArrayList<>();
            declared.addAll(governance
                    .declareConsumerQueue(
                            new ConsumerQueueSpec("it", PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(
                            new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItOrderFlowCaptureListener itOrderFlowCaptureListener(EventEnvelopeCodec codec) {
            return new ItOrderFlowCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（按事件类型分列表收帧，不登记 received_event）。 */
    static class ItOrderFlowCaptureListener {

        private final EventEnvelopeCodec codec;

        ItOrderFlowCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_DISPENSE_COMPLETED, ItCaptureConfig.Q_EXECUTION_COMPLETED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.DISPENSE_COMPLETED.add(envelope);
            } else if (NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.EXECUTION_COMPLETED.add(envelope);
            }
        }
    }

    /** 带 Bearer 的 GET 助手（InpatientOrderFlowIT 同型）。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
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

    /** 建项目+定价+发布一步到位（OutpatientFullFlowIT 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 五环节项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "盒")
                .put("comboFlag", false)
                .put("feeCategory", "DRUG_FEE");
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

    /** 药品建档（口服途径承载——SINGLE_DOSE 判定锚）。 */
    private long createDrug(String drugCode) {
        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", drugCode)
                .put("genericName", "IT 五环节药品 " + drugCode)
                .put("dosageForm", "片剂")
                .put("specification", "0.25g×24")
                .put("unit", "盒")
                .putArray("routeCodes")
                .add("ORAL");
        drug.put("essentialFlag", false)
                .put("antibioClass", "NONE")
                .put("hazardLevel", "NONE")
                .put("skinTestFlag", false)
                .put("narcoticClass", "NORMAL")
                .put("itemCode", drugCode);
        return postJson("/api/v1/pharmacy/drugs", adminToken, drug).path("id").asLong();
    }

    /** 批次 SQL 直插（V703 零数据行——IT 造数红线偏差⑧先例口径；id 自带——MP ASSIGN_ID 应用层
     * 雪花无库端默认，jdbcTemplate 直插须显式主键，PharmacyDispenseGuardIT 同型）。 */
    private void insertBatch(long batchId, long drugId, String batchNo, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO pharmacy.drug_batch (id, drug_id, storehouse, batch_no, production_date,"
                        + " expire_date, quantity, locked_qty, status)"
                        + " VALUES (?, ?, 'OUTP_PHARM', ?, ?, ?, ?, 0, 'IN_STOCK')",
                batchId,
                drugId,
                batchNo,
                java.sql.Date.valueOf("2025-06-01"),
                java.sql.Date.valueOf("2027-06-01"),
                quantity);
    }

    /** 轮询等待医嘱到达目标状态（审方回执驱动异步收敛，禁盲等——InpatientOrderFlowIT 同型）。 */
    private void awaitOrderStatus(String orderNoText, String expected) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(jdbcTemplate.queryForObject(
                    "SELECT status FROM inpatient.medical_order WHERE order_no = ?", String.class, orderNoText))) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("医嘱状态迁移超时：orderNo=" + orderNoText + "，期望 " + expected);
    }

    /** 该医嘱执行单行总数。 */
    private int totalRows() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND deleted = 0",
                Integer.class,
                orderNo);
        return n == null ? 0 : n;
    }

    /** 轮询等待业务条件成立（消费链异步收敛）。 */
    private void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
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

    /** 轮询等待捕获列表出现指定类型且载荷锚定医嘱号的帧。 */
    private EventEnvelope awaitCaptured(List<EventEnvelope> captured, String eventType, String orderNoAnchor) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            EventEnvelope hit = captured.stream()
                    .filter(e -> eventType.equals(e.eventType()))
                    .filter(e ->
                            orderNoAnchor.equals(e.payload().path("m04OrderNo").asText()))
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                return hit;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventType + "（m04OrderNo=" + orderNoAnchor + "）");
    }

    @Test
    @Order(1)
    @DisplayName("前置：三账号/定价/药品批次/患者床位/入院链四步至 ADMITTED+投影在区")
    void prepareStackAndAdmitPatient() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        newItemWithPrice(ORAL_DRUG_CODE, 3000);
        long drugId = createDrug(ORAL_DRUG_CODE);
        insertBatch(910411L, drugId, "B-IT-ORAL-A", 10);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '2', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 五环节患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, 'IT9-41', ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
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
                // 期望入住日按北京钟面取当日（时区红线）
                .put("expectDate", LocalDate.now(TimeConstants.HEALTHCARE_TZ).toString());
        postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule);
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_ID).put("bedId", BED_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitUntil(
                "投影行在区（执行单生成归属前置）",
                () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0",
                                Integer.class,
                                visitId)
                        >= 1);
    }

    @Test
    @Order(2)
    @DisplayName("医嘱链：开立口服长期医嘱→审方通过→转抄→次日计划→执行单（快照+计划行）")
    void openOralLongOrderAndGenerateExecutions() throws Exception {
        // 开立 DRUG 长期医嘱（freqCode=qd，route=口服——SINGLE_DOSE 判定锚）
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "DRUG").put("orderClass", "LONG").put("freqCode", "qd");
        ObjectNode line = order.putArray("items").addObject();
        line.put("itemType", "DRUG")
                .put("itemCode", ORAL_DRUG_CODE)
                .put("itemName", "IT 五环节药品")
                .put("quantity", "1")
                .put("dosage", "0.25")
                .put("dosageUnit", "g")
                .put("route", "口服");
        JsonNode vo = postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order);
        orderNo = vo.path("orderNo").asText();
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");

        // 审方通过（工作台轮询取任务 id→reviewer 审批位）
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
        // 审方回执驱动 CREATED→AUDITED 异步收敛（禁盲取状态——转抄仅 AUDITED 态可入）
        awaitOrderStatus(orderNo, "AUDITED");

        // 转抄核对放行→TRANSFERRED→nursing 快照执行单落位
        ObjectNode check = objectMapper.createObjectNode();
        check.putArray("orderNos").add(orderNo);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED").put("secondCheckerId", "IT-NURSE-2");
        assertThat(postForEntity("/api/v1/inpatient/orders/transfer-check", adminToken, check)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitUntil("快照执行单落位", () -> totalRows() >= 1);

        // 次日计划预置（日切服务直调等价 02:30 任务面）→计划执行单（驱动载体取最晚行）
        int created = orderPlanService.decomposeNextDay(
                LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1));
        assertThat(created).as("长期 qd 次日计划应生成一行").isEqualTo(1);
        awaitUntil(
                "计划执行单落位",
                () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND m04_plan_no IS NOT NULL AND deleted = 0",
                                Integer.class,
                                orderNo)
                        >= 1);
        Map<String, Object> planRow = jdbcTemplate.queryForMap(
                "SELECT execution_no, m04_plan_no FROM nursing.order_execution"
                        + " WHERE m04_order_no = ? AND m04_plan_no IS NOT NULL AND deleted = 0"
                        + " ORDER BY plan_time DESC LIMIT 1",
                orderNo);
        planExecutionNo = (String) planRow.get("execution_no");
        m04PlanNo = (String) planRow.get("m04_plan_no");
        assertThat(m04PlanNo).as("计划号契约 PL+yyyyMMdd+5 位流水").matches("PL\\d{13}");
    }

    @Test
    @Order(3)
    @DisplayName("摆药五步：SINGLE_DOSE 计划→pick→verify（异人双签）→issue→deliver（半步不迁状态）→receive")
    void dispenseFiveStepsWithTimelineHalfStep() {
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("m04OrderNo", orderNo).put("wardId", WARD_ID);
        JsonNode plans = postJson("/api/v1/pharmacy/dispense-plans/generate", adminToken, generate);
        assertThat(plans.isArray()).as("生成应返回计划清单").isTrue();
        assertThat(plans.size()).as("qd 次日一计划").isEqualTo(1);
        dispensePlanNo = plans.get(0).path("planNo").asText();
        assertThat(plans.get(0).path("planType").asText()).as("口服用法→单剂量摆药判定").isEqualTo("SINGLE_DOSE");

        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/pick",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/verify",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/issue",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/deliver",
                                adminToken,
                                objectMapper.createObjectNode().put("carrier", "IT-配送员"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // ledger 义务：deliver 仅 issued_at 时间线半步（Task 8 裁决——不迁状态）
        Map<String, Object> afterDeliver = jdbcTemplate.queryForMap(
                "SELECT status, issued_at FROM pharmacy.dispense_plan WHERE plan_no = ?", dispensePlanNo);
        assertThat(afterDeliver.get("status")).as("配送交接保持 CHECKED").isEqualTo("CHECKED");
        assertThat(afterDeliver.get("issued_at")).as("配送时间线 issued_at 已置位").isNotNull();
        // 出库流水勾稽：ISSUE 负行已落账（refDoc=计划号）
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE ref_doc = ? AND action = 'ISSUE' AND deleted = 0",
                Integer.class,
                dispensePlanNo);
        assertThat(ledgerRows).as("出库流水一行（库存扣减同事务落账）").isEqualTo(1);

        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/receive",
                                adminToken,
                                objectMapper.createObjectNode().put("receivedBy", 5))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitUntil("计划行 DELIVERED（签收迁移）", () -> "DELIVERED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM pharmacy.dispense_plan WHERE plan_no = ?", String.class, dispensePlanNo)));

        // dispense.completed 帧（住院四字段载荷勾稽——V1111 扩列）
        EventEnvelope completed = awaitCaptured(
                ItCaptureConfig.DISPENSE_COMPLETED, PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED, orderNo);
        assertThat(completed.payload().path("dispenseType").asText())
                .as("口服链类型")
                .isEqualTo("INPATIENT_DOSE");
        assertThat(completed.payload().path("dispensePlanNo").asText()).isEqualTo(dispensePlanNo);
        assertThat(completed.payload().path("visitId").asText()).isEqualTo(visitId);
    }

    @Test
    @Order(4)
    @DisplayName("签收衔接：执行单批量 SIGNED（非 PIVAS 零升格零建链）")
    void signoffSignsExecutionsWithoutUpgrade() {
        awaitUntil(
                "执行单全量 SIGNED",
                () -> totalRows()
                        == jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND status = 'SIGNED' AND deleted = 0",
                                Integer.class,
                                orderNo));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                + " AND execution_type != 'GENERIC' AND deleted = 0",
                        Integer.class,
                        orderNo))
                .as("口服链不触发 PIVAS 升格")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM nursing.infusion_monitor_link WHERE deleted = 0", Integer.class))
                .as("口服链不建监测挂接")
                .isZero();
    }

    @Test
    @Order(5)
    @DisplayName("五环节收口：腕带核对→开始（破码时间窗）→完成→COMPLETED+回签对账锚")
    void fiveSegmentFinishReconciles() {
        // 腕带三向核对（WRISTBAND 维：visitId 匹配）→CHECKED
        ObjectNode checkReq = objectMapper.createObjectNode();
        checkReq.put("code", visitId).put("codeType", "WRISTBAND");
        assertThat(postForEntity("/api/v1/nursing/executions/" + planExecutionNo + "/check", adminToken, checkReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 开始执行（次日计划时点窗外——口头医嘱现场确认面 overrideTimeWindow 承载）
        ObjectNode startReq = objectMapper.createObjectNode();
        startReq.put("executorId", 66).put("overrideTimeWindow", true);
        assertThat(postForEntity("/api/v1/nursing/executions/" + planExecutionNo + "/start", adminToken, startReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 执行完成（EXECUTING→COMPLETED+双路回签）
        assertThat(postForEntity(
                                "/api/v1/nursing/executions/" + planExecutionNo + "/finish",
                                adminToken,
                                objectMapper
                                        .createObjectNode()
                                        .put("executorId", 66)
                                        .put("routeCheckResult", "IT 五环节给药核对无误"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitUntil("执行单 COMPLETED", () -> "COMPLETED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        planExecutionNo)));
    }

    @Test
    @Order(6)
    @DisplayName("回签对账：M04 计划 EXECUTED（主路径）+回执帧可观测（辅路径）+对账零差异锚")
    void reconcileM04PlanWithZeroDiff() {
        // 主路径回签：M04 计划 PENDING→EXECUTED
        awaitUntil("M04 计划回签 EXECUTED", () -> "EXECUTED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.order_execute_plan WHERE plan_no = ?", String.class, m04PlanNo)));
        // 对账锚：confirm_status=CONFIRMED+全单零 COMPENSATING 残留（回签主路径直达的 DB 表达）
        awaitUntil("驱动行对账 CONFIRMED", () -> "CONFIRMED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT confirm_status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        planExecutionNo)));
        Integer compensating = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                        + " AND confirm_status = 'COMPENSATING' AND deleted = 0",
                Integer.class,
                orderNo);
        assertThat(compensating).as("对账零差异锚：全单无 COMPENSATING 残留").isZero();
        // M04 计划行值面：执行人/途径核对结论透传（W-33 存储落点）
        Map<String, Object> planValues = jdbcTemplate.queryForMap(
                "SELECT executor_id, route_check_result FROM inpatient.order_execute_plan WHERE plan_no = ?",
                m04PlanNo);
        assertThat(planValues.get("executor_id")).as("回签执行人透传").isEqualTo("66");
        assertThat(planValues.get("route_check_result")).as("给药途径核对结论透传").isEqualTo("IT 五环节给药核对无误");

        // 辅路径：回执帧可观测（id 64 载荷锚定——五时点+执行人+破码标志契约组件抽验）
        EventEnvelope receipt = awaitCaptured(
                ItCaptureConfig.EXECUTION_COMPLETED,
                NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED,
                orderNo);
        assertThat(receipt.payload().path("executionNo").asText()).isEqualTo(planExecutionNo);
        assertThat(receipt.payload().path("m04PlanNo").asText()).isEqualTo(m04PlanNo);
        assertThat(receipt.payload().path("executorId").asLong()).isEqualTo(66L);
        assertThat(receipt.payload().path("finishedAt").isMissingNode())
                .as("完成回执帧携带终态时点")
                .isFalse();
    }
}

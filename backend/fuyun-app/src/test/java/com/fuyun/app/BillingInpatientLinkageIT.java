package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.billing.api.BillingAccountQueryPort;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.integration.constants.MessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * PR-1 验收锚点⑤：M13 住院计费联动真栈（HTTP/MQ/DB 零 mock）——billing 通配队列六事件
 * 消费的业务终态与幂等/聚合面：①admitted→床位费 PENDING 行+入科起费锚点 ②created→逐明细
 * 离散计价（两行金额=种子价×数量；audited 帧到店不改计费状态面——计价承载面在 created，
 * Task 13 裁决）③executed→PENDING 确认 CONFIRMED ④stopped→PENDING 截断作废 ⑤transferred→
 * 归属切分行（from/to 病区）⑥同 eventId 重投幂等（构件幂等拦截，received_event 单行零重复
 * 计费）⑦precheck 聚合（未结清合计=PENDING+CONFIRMED、押金余额、结清布尔三组件契约）。
 *
 * <p>捕获形态：住院域事件族含子键帧，自声明 q.it.inpatient.#（BillingMessagingConfig 生产侧
 * 同款先例）；raw 解析监听不进幂等台账。容器三件套类级独占（GC9 红线）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingInpatientLinkageIT extends FuyunStackITBase {

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

    /** 计价项目码（A=2000 分、B=3000 分；床位费 6000 分来自 V1003 演示价种子） */
    private static final String ITEM_A = "IT-LNK-A";

    private static final String ITEM_B = "IT-LNK-B";

    /** 转出/转入病区与床位（归属切分行断言面） */
    private static final String WARD_FROM = "W-IT-9006";

    private static final String WARD_TO = "W-IT-9007";

    private static final long BED_FROM_ID = 920511L;

    private static final long BED_TO_ID = 920521L;

    private static final long PATIENT_ID = 920501L;

    /** 押金缴存额（分）——precheck 聚合的押金余额面 */
    private static final long DEPOSIT_FEN = 5000L;

    /** DB 轮询步进（计费行/锚点行异步收敛，100ms 步进轮询足够收敛，上限 10s 量级） */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态 */
    private static String adminToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String orderANo = "";

    private static String orderBNo = "";

    /** 住院域事件族通配捕获队列（自声明——governance 无通配通道，BillingMessagingConfig 同款先例）。 */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String CAPTURE_QUEUE = "q.it.inpatient.#";

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itInpatientWildcardQueue() {
            Queue queue = QueueBuilder.durable(CAPTURE_QUEUE)
                    .quorum()
                    .deadLetterExchange("fy.dlx")
                    .build();
            Binding binding = new Binding(
                    CAPTURE_QUEUE,
                    Binding.DestinationType.QUEUE,
                    MessagingConstants.EXCHANGE_TOPIC,
                    "inpatient.#",
                    null);
            return new Declarables(queue, binding);
        }

        @Bean
        ItCaptureListener itCaptureListener(EventEnvelopeCodec codec) {
            return new ItCaptureListener(codec);
        }
    }

    /** 监听器本体（测试侧轻量：解析入列，不登记 received_event）。 */
    static class ItCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = ItCaptureConfig.CAPTURE_QUEUE)
        void onMessage(Message message) {
            ItCaptureConfig.CAPTURED.add(codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8)));
        }
    }

    /** RabbitTemplate（生产同源 JSON 转换器）：重复投递帧重发载体。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 出院费用预审只读端口（billing 侧实现，precheck 聚合断言通道）。 */
    @Autowired
    private BillingAccountQueryPort billingAccountQueryPort;

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

    /** 带令牌 POST（返回原始响应实体，状态码断言）。 */
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
                .put("itemName", "IT 计费联动项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "TREAT_FEE");
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

    /** 开立一条 STAT NURSING 医嘱（多明细行，非用药类自动过审）。 */
    private String createNursingOrder(com.fasterxml.jackson.databind.node.ArrayNode items) {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "NURSING").put("orderClass", "STAT");
        order.set("items", items);
        return postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order)
                .path("orderNo")
                .asText();
    }

    /** 读该医嘱指定状态的费用行数（billing 计价行权威面）。 */
    private int feeRowCount(String orderNoText, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_record WHERE source_ref = ? AND visit_id = ?"
                        + " AND status = ? AND deleted = 0",
                Integer.class,
                orderNoText,
                visitId,
                status);
        return count == null ? 0 : count;
    }

    /** 轮询等待该医嘱指定状态费用行数达标（billing 通配消费异步收敛，禁盲等）。 */
    private void awaitFeeRowCount(String orderNoText, String status, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (feeRowCount(orderNoText, status) == expected) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("费用行未到位：orderNo=" + orderNoText + "，期望 " + status + "×" + expected);
    }

    /** 轮询等待指定事件帧到达捕获队列（AFTER_COMMIT 出 MQ 异步收敛）。 */
    private EventEnvelope awaitCaptured(String eventType) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<EventEnvelope> matched = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> e.eventType().equals(eventType))
                    .toList();
            if (!matched.isEmpty()) {
                return matched.get(0);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventType);
    }

    @Test
    @Order(1)
    @DisplayName("前置：登录/定价/患者两床两病区直插；入院链四步至 ADMITTED；押金 5000 分缴存")
    void prepareStackAndDeposit() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        doctorToken = loginToken("doctordemo");
        newItemWithPrice(ITEM_A, 2000);
        newItemWithPrice(ITEM_B, 3000);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "计费联动患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE'), (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_FROM_ID,
                "IT9-41",
                WARD_FROM,
                BED_TO_ID,
                "IT9-42",
                WARD_TO);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_FROM)
                .put("issuedDoctorId", "3");
        String admissionNo = postJson("/api/v1/inpatient/admissions", adminToken, create)
                .path("admissionNo")
                .asText();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_FROM)
                .put("targetBedId", BED_FROM_ID)
                .put("expectDate", LocalDate.now().toString());
        postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule);
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        assertThat(visitId).as("I 型 14 位 visit_id（计费键定位面）").matches("I\\d{13}");
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_FROM).put("bedId", BED_FROM_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 押金缴存（precheck 押金余额面）
        ObjectNode deposit = objectMapper.createObjectNode();
        deposit.put("patientId", PATIENT_ID)
                .put("visitId", visitId)
                .put("amountFen", DEPOSIT_FEN)
                .put("paymentMethod", "CASH");
        assertThat(postForEntity("/api/v1/billing/deposits", adminToken, deposit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("admitted 起费：床位费 PENDING 行（IN_BED_DAY 6000 分/DURATION）+ ADMIT_START 起费锚点行")
    void admittedGeneratesBedFeeAndAnchor() throws Exception {
        for (int i = 0; i < 100; i++) {
            Integer bedFee = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND item_name_snapshot = ?"
                            + " AND status = 'PENDING' AND amount = 6000 AND deleted = 0",
                    Integer.class,
                    visitId,
                    "普通床位费");
            if (bedFee != null && bedFee == 1) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        Integer bedFee = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND item_name_snapshot = ?"
                        + " AND status = 'PENDING' AND amount = 6000 AND deleted = 0",
                Integer.class,
                visitId,
                "普通床位费");
        assertThat(bedFee).as("入科应生成当日床位费 PENDING 行（V1003 演示价 6000 分）").isEqualTo(1);

        Integer anchors = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_ownership_split WHERE visit_id = ?"
                        + " AND split_type = 'ADMIT_START' AND to_ward_id = ? AND from_ward_id IS NULL AND deleted = 0",
                Integer.class,
                visitId,
                WARD_FROM);
        assertThat(anchors).as("入科起费锚点 ADMIT_START 恰一行（to_ward=入科病区）").isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("created 离散计价：单医嘱两明细→两行金额=种子价×数量；audited 帧到店不改计费状态面")
    void createdPricesDiscreteAndAuditedIsNoop() throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode items = objectMapper.createArrayNode();
        items.addObject()
                .put("itemType", "NURSING")
                .put("itemCode", ITEM_A)
                .put("itemName", "IT 项目 A")
                .put("quantity", "1");
        items.addObject()
                .put("itemType", "NURSING")
                .put("itemCode", ITEM_B)
                .put("itemName", "IT 项目 B")
                .put("quantity", "2");
        orderANo = createNursingOrder(items);

        // created 消费计价：两行 PENDING，金额=2000×1 与 3000×2
        awaitFeeRowCount(orderANo, "PENDING", 2);
        List<Map<String, Object>> amounts = jdbcTemplate.queryForList(
                "SELECT amount FROM billing.fee_record WHERE source_ref = ? AND visit_id = ?"
                        + " AND status = 'PENDING' AND deleted = 0 ORDER BY amount",
                orderANo,
                visitId);
        assertThat(amounts)
                .extracting(row -> ((Number) row.get("amount")).longValue())
                .as("两行金额=种子价×数量（2000×1 与 3000×2）")
                .containsExactly(2000L, 6000L);

        // audited 帧到店（自动过审链路真实发布）不改计费状态面：行数仍 2、状态仍 PENDING
        awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_AUDITED + ".nursing");
        assertThat(feeRowCount(orderANo, "PENDING"))
                .as("audited 帧零业务动作（计价承载面在 created）")
                .isEqualTo(2);
        assertThat(feeRowCount(orderANo, "CONFIRMED")).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("executed 费用确认：转抄+回签→executed 帧→该医嘱 PENDING 行全 CONFIRMED")
    void executedConfirmsOrderFees() throws Exception {
        ObjectNode check = objectMapper.createObjectNode();
        var arr = check.putArray("orderNos");
        arr.add(orderANo);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED");
        assertThat(postForEntity("/api/v1/inpatient/orders/transfer-check", adminToken, check)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 计划按明细行粒度开立：两明细行→两计划行，逐行回签
        List<String> planNos = jdbcTemplate.queryForList(
                "SELECT plan_no FROM inpatient.order_execute_plan"
                        + " WHERE order_id = (SELECT id FROM inpatient.medical_order WHERE order_no = ?)"
                        + " AND deleted = 0",
                String.class,
                orderANo);
        assertThat(planNos).as("两明细行应有两计划行").hasSize(2);
        for (String planNo : planNos) {
            assertThat(postForEntity(
                                    "/api/v1/inpatient/order-plans/" + planNo + "/execute-confirm",
                                    adminToken,
                                    objectMapper.createObjectNode().put("executorId", 66))
                            .getStatusCode()
                            .is2xxSuccessful())
                    .isTrue();
        }

        awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_EXECUTED);
        awaitFeeRowCount(orderANo, "CONFIRMED", 2);
        assertThat(feeRowCount(orderANo, "PENDING")).as("确认后无残留 PENDING 行").isZero();
    }

    @Test
    @Order(5)
    @DisplayName("stopped 费用截断：第二条医嘱停嘱→其 PENDING 行作废（CONFIRMED 行不回冲不可逆）")
    void stoppedCancelsPendingFees() throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode items = objectMapper.createArrayNode();
        items.addObject()
                .put("itemType", "NURSING")
                .put("itemCode", ITEM_B)
                .put("itemName", "IT 项目 B")
                .put("quantity", "1");
        orderBNo = createNursingOrder(items);
        awaitFeeRowCount(orderBNo, "PENDING", 1);

        assertThat(postForEntity(
                                "/api/v1/inpatient/orders/" + orderBNo + "/stop",
                                doctorToken,
                                objectMapper.createObjectNode().put("reason", "IT 验收：停费截断"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitFeeRowCount(orderBNo, "CANCELLED", 1);

        // 已确认行不回冲：医嘱 A 的 CONFIRMED 行不受他人停嘱影响
        assertThat(feeRowCount(orderANo, "CONFIRMED")).as("停嘱只截断在途行").isEqualTo(2);
    }

    @Test
    @Order(6)
    @DisplayName("transferred 归属切分：目标床预占+转科编排→TRANSFER 切分行携 from/to 病区")
    void transferredWritesSplitRow() throws Exception {
        assertThat(postForEntity(
                                "/api/v1/inpatient/beds/" + BED_TO_ID + "/reserve",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        ObjectNode transfer = objectMapper.createObjectNode();
        transfer.put("toWardId", WARD_TO).put("toBedId", BED_TO_ID);
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/transfer", adminToken, transfer)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitCaptured(InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED);

        for (int i = 0; i < 100; i++) {
            Integer splits = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM billing.fee_ownership_split WHERE visit_id = ?"
                            + " AND split_type = 'TRANSFER' AND from_ward_id = ? AND to_ward_id = ? AND deleted = 0",
                    Integer.class,
                    visitId,
                    WARD_FROM,
                    WARD_TO);
            if (splits != null && splits == 1) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        Integer splits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_ownership_split WHERE visit_id = ?"
                        + " AND split_type = 'TRANSFER' AND from_ward_id = ? AND to_ward_id = ? AND deleted = 0",
                Integer.class,
                visitId,
                WARD_FROM,
                WARD_TO);
        assertThat(splits).as("转科应落 TRANSFER 归属切分行（时间线记录面，无费用动作）").isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("重复投递幂等：同 eventId 原帧重发 fy.topic→构件幂等拦截，零重复计费 received_event 单行")
    void duplicateFrameIsIdempotentlyIntercepted() throws Exception {
        // 取 created.nursing 原帧（capture 队列收到的首帧）作重投载体
        EventEnvelope created = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_CREATED + ".nursing");
        Integer receivedBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                java.util.UUID.fromString(created.eventId()),
                "billing");
        assertThat(receivedBefore).as("原帧已由 billing 成功消费（received_event 单行）").isEqualTo(1);

        // 同 eventId 原帧重投 fy.topic（routing key=投递面 eventType 携子键）
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, created.eventType(), created);

        // 捕获队列应再收一帧（证明重投帧已入交换机扇出），billing 侧幂等拦截零业务
        for (int i = 0; i < 100; i++) {
            long dupCaptured = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> e.eventId().equals(created.eventId()))
                    .count();
            if (dupCaptured >= 2) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        long dupCaptured = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> e.eventId().equals(created.eventId()))
                .count();
        assertThat(dupCaptured).as("重投帧应再次扇出至捕获队列").isGreaterThanOrEqualTo(2);

        // 有界稳定窗：幂等拦截下计费行数与台账行数恒定（3s 窗内零漂移）
        for (int i = 0; i < 30; i++) {
            assertThat(feeRowCount(orderANo, "PENDING")).isZero();
            assertThat(feeRowCount(orderANo, "CONFIRMED")).isEqualTo(2);
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        Integer receivedAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                java.util.UUID.fromString(created.eventId()),
                "billing");
        assertThat(receivedAfter).as("同 eventId 台账仍单行（构件幂等语义验证）").isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("precheck 聚合：未结清=床位6000(PENDING)+A确认两行8000；押金 5000；settled=false")
    void precheckAggregatesUnsettledAndDeposit() {
        // 终态费用面：床位费 PENDING 6000 + A 两行 CONFIRMED 8000（B 已作废不进合计）
        var view = billingAccountQueryPort.precheck(visitId);
        assertThat(view.unsettledAmount()).as("未结清合计=PENDING+CONFIRMED 未结算行").isEqualTo(14000L);
        assertThat(view.depositBalance()).as("押金余额=缴存快照").isEqualTo(DEPOSIT_FEN);
        assertThat(view.settled()).as("未结清 14000 > 押金 5000 应判定未结清").isFalse();
    }
}

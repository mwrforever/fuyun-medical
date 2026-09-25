package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.service.OrderPlanService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * PR-1 验收锚点③：转科五步编排与出院全链真栈（HTTP/MQ/DB 零 mock）。
 *
 * <p>转科五步：目标床位预占（FREE→RESERVED）→编排执行（转出长期医嘱自动停嘱[stopped 帧
 * reason=转科]/在途三分[临时 PENDING 计划保留且病区重定向、长期 PENDING 计划作废]/床位流转
 * [转出床 DISINFECTING、目标床 OCCUPIED]/定位 CAS→inpatient.visit.transferred 载荷字段逐项勾稽）。
 *
 * <p>出院全链：出院申请（在途清理+费用预审 BLOCKED 欠费额快照）→挂账审批放行（billing
 * approve→arrears.approved id 73 真投递→BLOCKED→READY）→双条件拒绝（READY 未结算确认
 * 409 IP-1017）→真实结算（IT 内直调 billing 结算 API 产生 settlement.completed 真事件→
 * 结算标记回填）→离院确认（DISCHARGED+床位 DISINFECTING+discharged 帧投递+出院带药放行
 * [audited.discharge-med 子键帧]+随访计划生成）。
 *
 * <p>捕获形态：住院域事件族含子键帧（audited.discharge-med），governance 无法绑定通配段
 * （BillingMessagingConfig 生产侧自声明同款先例）——自声明 q.it.inpatient.#（durable quorum +
 * fy.dlx 死信 + 绑定 fy.topic），raw 解析监听不进幂等台账。容器三件套类级独占（GC9 红线）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InpatientTransferDischargeIT extends FuyunStackITBase {

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

    /** 计价项目码（护理 4000 分/出院带药 1000 分——欠费预审 BLOCKED 的费用来源） */
    private static final String NURSING_ITEM_CODE = "IT-NUR-001";

    private static final String DISCHARGE_MED_ITEM_CODE = "IT-DIS-001";

    /** 转出/转入病区与床位（SQL 直插主数据） */
    private static final String WARD_FROM = "W-IT-9003";

    private static final String WARD_TO = "W-IT-9004";

    private static final long BED_FROM_ID = 920311L;

    private static final long BED_TO_ID = 920321L;

    private static final long PATIENT_ID = 920301L;

    /** V704 演示医师（sys_user id=3 持 PRESCRIPTION 执业授权——开单操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** MQ 链路与消费面等待上限/DB 轮询步进 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(10);

    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态 */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String longOrderNo = "";

    private static String statOrderNo = "";

    private static String dischargeMedOrderNo = "";

    private static String requestNo = "";

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
                    com.fuyun.integration.constants.MessagingConstants.EXCHANGE_TOPIC,
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

    /** 日切分解服务（转科前置：为长期医嘱排程次日计划，作「长期计划作废」断言的预置数据面）。 */
    @Autowired
    private OrderPlanService orderPlanService;

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

    /** 建项目+定价+发布一步到位（OutpatientFullFlowIT 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 转出降项目 " + itemCode)
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

    /** 开立一条医嘱（doctordemo 持权；非药品行免剂量三要素）。 */
    private JsonNode createOrder(String orderType, String orderClass, String freqCode, String itemCode) {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", orderType).put("orderClass", orderClass);
        if (freqCode != null) {
            order.put("freqCode", freqCode);
        }
        order.putArray("items")
                .addObject()
                .put("itemType", orderType)
                .put("itemCode", itemCode)
                .put("itemName", "IT 项目 " + itemCode)
                .put("quantity", "1");
        return postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order);
    }

    /** 批量转抄核对放行。 */
    private void transferCheck(List<String> orderNos) {
        ObjectNode check = objectMapper.createObjectNode();
        var orderNoArray = check.putArray("orderNos");
        orderNos.forEach(orderNoArray::add);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED");
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

    /** 轮询等待捕获队列出现指定前缀帧并返回首帧（AFTER_COMMIT 出 MQ 异步收敛）。 */
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

    /** 轮询等待出院申请到达目标状态并返回行（billing 回执事件驱动异步收敛）。 */
    private Map<String, Object> awaitRequestStatus(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, arrears_amount, settlement_completed_at, approval_no FROM inpatient.discharge_request"
                            + " WHERE request_no = ? AND deleted = 0",
                    requestNo);
            if (expected.equals(row.get("status"))) {
                return row;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("出院申请状态迁移超时：requestNo=" + requestNo + "，期望 " + expected);
    }

    @Test
    @Order(1)
    @DisplayName("前置：登录/定价/患者两床两病区直插；入院链四步至 ADMITTED（W1-B1）")
    void prepareAndAdmit() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        newItemWithPrice(NURSING_ITEM_CODE, 4000);
        newItemWithPrice(DISCHARGE_MED_ITEM_CODE, 1000);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '2', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "转科出院患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE'), (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_FROM_ID,
                "IT9-11",
                WARD_FROM,
                BED_TO_ID,
                "IT9-21",
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
        assertThat(visitId).as("I 型 14 位 visit_id").matches("I\\d{13}");
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_FROM).put("bedId", BED_FROM_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("开立三医嘱并转抄两条：长期 qd 转抄+次日计划预置（decomposeNextDay）、临时转抄单次计划、出院带药停留 CREATED")
    void openOrdersAndPreparePlans() {
        JsonNode longOrder = createOrder("NURSING", "LONG", "qd", NURSING_ITEM_CODE);
        longOrderNo = longOrder.path("orderNo").asText();
        JsonNode statOrder = createOrder("NURSING", "STAT", null, NURSING_ITEM_CODE);
        statOrderNo = statOrder.path("orderNo").asText();
        JsonNode dischargeMed = createOrder("DISCHARGE_MED", "STAT", null, DISCHARGE_MED_ITEM_CODE);
        dischargeMedOrderNo = dischargeMed.path("orderNo").asText();
        assertThat(longOrder.path("status").asText()).as("非用药类自动过审").isEqualTo("AUDITED");
        assertThat(statOrder.path("status").asText()).isEqualTo("AUDITED");
        // 出院带药属用药类（OrderType.isMedication）：停留 CREATED 待放行面（确认时放行 AUDITED）
        assertThat(dischargeMed.path("status").asText()).isEqualTo("CREATED");
        transferCheck(List.of(longOrderNo, statOrderNo));
        assertThat(orderStatus(longOrderNo)).isEqualTo("TRANSFERRED");
        assertThat(orderStatus(statOrderNo)).isEqualTo("TRANSFERRED");

        // 长期医嘱次日计划预置（日切服务直调，等价 02:30 任务面）：qd→次日 08:00 一行 PENDING
        int created = orderPlanService.decomposeNextDay(LocalDate.now().plusDays(1));
        assertThat(created).as("长期 qd 次日计划应生成一行").isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("转科五步：目标床预占→编排执行——长期停嘱[转科]/临时计划保留重定向/长期计划作废/床位流转/transferred 载荷勾稽")
    void transferFiveSteps() throws Exception {
        // 第 0 步：目标床位预占（登记台签床同款 FREE→RESERVED）
        assertThat(postForEntity(
                                "/api/v1/inpatient/beds/" + BED_TO_ID + "/reserve",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.bed WHERE id = ?", String.class, BED_TO_ID))
                .isEqualTo("RESERVED");

        // 编排执行：单事务五步
        ObjectNode transfer = objectMapper.createObjectNode();
        transfer.put("toWardId", WARD_TO).put("toBedId", BED_TO_ID);
        JsonNode vo = postJson("/api/v1/inpatient/visits/" + visitId + "/transfer", adminToken, transfer);
        assertThat(vo.path("visitId").asText()).isEqualTo(visitId);
        assertThat(vo.path("toWardId").asText()).isEqualTo(WARD_TO);
        assertThat(vo.path("toBedId").asLong()).isEqualTo(BED_TO_ID);
        assertThat(vo.hasNonNull("transferredAt")).isTrue();

        // ①转出长期医嘱自动停嘱：STOPPED + stop_reason=转科 + stopped 帧（转科固定文案）
        assertThat(orderStatus(longOrderNo)).as("转出长期医嘱应自动停嘱").isEqualTo("STOPPED");
        String stopReason = jdbcTemplate.queryForObject(
                "SELECT stop_reason FROM inpatient.medical_order WHERE order_no = ?", String.class, longOrderNo);
        assertThat(stopReason).isEqualTo("转科");
        EventEnvelope stopped = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_STOPPED);
        assertThat(stopped.payload().path("m04OrderNo").asText()).isEqualTo(longOrderNo);
        assertThat(stopped.payload().path("stopReason").asText()).isEqualTo("转科");

        // ②在途三分：临时 PENDING 计划保留且病区重定向至转入病区；长期 PENDING 计划作废
        Integer statPending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.status = 'PENDING' AND p.ward_id = ? AND p.deleted = 0",
                Integer.class,
                statOrderNo,
                WARD_TO);
        assertThat(statPending).as("临时计划应保留并重定向至转入病区").isEqualTo(1);
        Integer longPending = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.status = 'PENDING' AND p.deleted = 0",
                Integer.class,
                longOrderNo);
        assertThat(longPending).as("长期计划应全部作废").isZero();

        // ③④床位流转 + 定位 CAS：转出床 DISINFECTING、目标床 OCCUPIED、visit 定位新病区
        Map<String, Object> bedFrom =
                jdbcTemplate.queryForMap("SELECT status, visit_id FROM inpatient.bed WHERE id = ?", BED_FROM_ID);
        assertThat(bedFrom.get("status")).as("转出床应进入消毒态").isEqualTo("DISINFECTING");
        Map<String, Object> bedTo =
                jdbcTemplate.queryForMap("SELECT status, visit_id FROM inpatient.bed WHERE id = ?", BED_TO_ID);
        assertThat(bedTo.get("status")).as("目标床应被占床").isEqualTo("OCCUPIED");
        assertThat(bedTo.get("visit_id")).isEqualTo(visitId);
        Map<String, Object> visit = jdbcTemplate.queryForMap(
                "SELECT current_ward_id, current_bed_id, status FROM inpatient.inpatient_visit WHERE visit_id = ?",
                visitId);
        assertThat(visit.get("current_ward_id")).isEqualTo(WARD_TO);
        assertThat(visit.get("current_bed_id")).isEqualTo((Object) BED_TO_ID);
        assertThat(visit.get("status")).isEqualTo("ADMITTED");

        // ⑤transferred 帧载荷逐项勾稽（V800 id 49）
        EventEnvelope transferred = awaitCaptured(InpatientMessagingConstants.EVENT_VISIT_TRANSFERRED);
        assertThat(transferred.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(transferred.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(transferred.payload().path("fromWardId").asText()).isEqualTo(WARD_FROM);
        assertThat(transferred.payload().path("fromBedId").asLong()).isEqualTo(BED_FROM_ID);
        assertThat(transferred.payload().path("toWardId").asText()).isEqualTo(WARD_TO);
        assertThat(transferred.payload().path("toBedId").asLong()).isEqualTo(BED_TO_ID);
        assertThat(transferred.payload().hasNonNull("transferredAt")).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("出院申请：在途清理（临时计划作废/带药追踪）+ 费用预审 BLOCKED 欠费额=未结清-押金")
    void dischargeRequestClearanceAndPrecheck() {
        ObjectNode create = objectMapper.createObjectNode();
        create.put("expectDischargeAt", OffsetDateTime.now().toString()).put("dischargeWay", "1");
        JsonNode vo = postJson("/api/v1/inpatient/visits/" + visitId + "/discharge-request", doctorToken, create);
        requestNo = vo.path("requestNo").asText();
        assertThat(requestNo).as("出院申请单号契约 DC+yyyyMMdd+5 位").matches("DC\\d{13}");
        assertThat(vo.path("status").asText()).as("欠费预审应 BLOCKED").isEqualTo("BLOCKED");
        assertThat(vo.path("arrearsAmount").asLong())
                .as("欠费额=床位费6000+护理4000+带药1000-押金0")
                .isEqualTo(11000L);
        assertThat(vo.path("approvalNo").isNull()).isTrue();

        // 清理面：临时 PENDING 计划全作废；visit 进出院申请态；带药 CREATED 入追踪清单
        Integer pendingPlans = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.inpatient_visit v ON v.id = p.visit_id"
                        + " WHERE v.visit_id = ? AND p.status = 'PENDING' AND p.deleted = 0",
                Integer.class,
                visitId);
        assertThat(pendingPlans).as("出院申请应作废全部在途计划").isZero();
        String visitStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM inpatient.inpatient_visit WHERE visit_id = ?", String.class, visitId);
        assertThat(visitStatus).isEqualTo("DISCHARGE_REQUESTED");

        // 追踪清单读面：带药 CREATED（无合法停嘱边）在册
        JsonNode clearance = getJson("/api/v1/inpatient/discharge-requests/" + requestNo + "/clearance", adminToken);
        assertThat(clearance.path("trackedOrders").toString()).contains(dischargeMedOrderNo);
    }

    @Test
    @Order(5)
    @DisplayName("挂账审批放行：billing 创建+approve→arrears.approved id 73 真投递→申请 BLOCKED→READY 携审批单号")
    void arrearsApprovalReleasesBlock() throws Exception {
        ObjectNode create = objectMapper.createObjectNode();
        create.put("visitId", visitId).put("applyReason", "IT 验收：欠费挂账放行");
        JsonNode approval = postJson("/api/v1/billing/arrears-approvals", adminToken, create);
        String approvalNo = approval.path("approvalNo").asText();
        assertThat(approval.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(postForEntity(
                                "/api/v1/billing/arrears-approvals/" + approvalNo + "/approve",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // arrears.approved（V1002 id 73）真投递→inpatient 消费→BLOCKED→READY（帧本体经 billing 发布器，DB 终态为投递证据）
        Map<String, Object> row = awaitRequestStatus("READY");
        assertThat(row.get("approval_no")).isEqualTo(approvalNo);
    }

    @Test
    @Order(6)
    @DisplayName("双条件拒绝：READY 且未结算确认→409 ProblemDetail errorCode=IP-1017")
    void confirmBeforeSettlementRejected() {
        ResponseEntity<String> rejected = postForEntity(
                "/api/v1/inpatient/discharge-requests/" + requestNo + "/confirm",
                doctorToken,
                objectMapper.createObjectNode().put("followUpDays", 7));
        assertThat(rejected.getStatusCode().value()).as("未结算确认应 409 拒绝").isEqualTo(409);
        assertThat(toNode(rejected.getBody()).path("errorCode").asText()).isEqualTo("IP-1017");
    }

    @Test
    @Order(7)
    @DisplayName("真实结算：IT 直调 billing preview+settle→settlement.completed 真事件→结算标记回填申请行")
    void realSettlementMarksCompleted() throws Exception {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", visitId).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        String settleNo = pv.path("settleNo").asText();
        assertThat(pv.path("totalAmount").asLong()).as("未结清合计=11000 分").isEqualTo(11000L);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", settleNo);
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "11000");
        assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // settlement.completed 真投递→inpatient 消费→结算标记回填（双条件之二就位）
        for (int i = 0; i < 100; i++) {
            Object completedAt = jdbcTemplate.queryForObject(
                    "SELECT settlement_completed_at FROM inpatient.discharge_request WHERE request_no = ?",
                    Object.class,
                    requestNo);
            if (completedAt != null) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("出院结算标记回填超时：requestNo=" + requestNo);
    }

    @Test
    @Order(8)
    @DisplayName("离院确认：DISCHARGED+床位 DISINFECTING+discharged 帧+带药放行[audited.discharge-med]+随访 7 日生成")
    void confirmDischargeFullChain() throws Exception {
        JsonNode vo = postJson(
                "/api/v1/inpatient/discharge-requests/" + requestNo + "/confirm",
                doctorToken,
                objectMapper.createObjectNode().put("followUpDays", 7).put("followUpWay", "PHONE"));
        assertThat(vo.path("status").asText()).as("离院确认后申请终态").isEqualTo("COMPLETED");

        // visit 终态 + 床位终末消毒流转
        Map<String, Object> visit = jdbcTemplate.queryForMap(
                "SELECT status, discharged_at, discharge_way FROM inpatient.inpatient_visit WHERE visit_id = ?",
                visitId);
        assertThat(visit.get("status")).isEqualTo("DISCHARGED");
        assertThat(visit.get("discharge_way")).as("离院方式誊写病案口径").isEqualTo("1");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.bed WHERE id = ?", String.class, BED_TO_ID))
                .as("离院后床位进消毒态")
                .isEqualTo("DISINFECTING");

        // 出院带药放行：DISCHARGE_MED CREATED→AUDITED + 子键帧（放行即确认降级口径）
        assertThat(orderStatus(dischargeMedOrderNo)).isEqualTo("AUDITED");
        EventEnvelope released = awaitCaptured(InpatientMessagingConstants.EVENT_ORDER_AUDITED + ".discharge-med");
        assertThat(released.payload().path("m04OrderNo").asText()).isEqualTo(dischargeMedOrderNo);
        assertThat(released.payload().path("auditType").asText()).isEqualTo("SYSTEM");

        // discharged 帧（V800 id 51 载荷三组件）
        EventEnvelope discharged = awaitCaptured(InpatientMessagingConstants.EVENT_VISIT_DISCHARGED);
        assertThat(discharged.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(discharged.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(discharged.payload().hasNonNull("dischargedAt")).isTrue();

        // 随访计划：出院日后 7 日一行 PENDING（随访日期以 discharged_at 同源 JDBC 承载推算）
        Map<String, Object> followUp = jdbcTemplate.queryForMap(
                "SELECT plan_date, way, status FROM inpatient.follow_up_plan WHERE visit_id ="
                        + " (SELECT id FROM inpatient.inpatient_visit WHERE visit_id = ?) AND deleted = 0",
                visitId);
        LocalDate expectedDate = ((Timestamp) visit.get("discharged_at"))
                .toInstant()
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(7);
        LocalDate planDate = ((java.sql.Date) followUp.get("plan_date")).toLocalDate();
        assertThat(planDate).as("随访日期=出院日+7 日").isEqualTo(expectedDate);
        assertThat(followUp.get("way")).isEqualTo("PHONE");
        assertThat(followUp.get("status")).isEqualTo("PENDING");
    }
}

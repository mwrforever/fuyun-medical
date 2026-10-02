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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-3 验收锚点③（转科重定向与回签对账，真栈 HTTP/MQ/DB 零 mock）：长期医嘱执行单生成（转抄
 * 快照+次日计划行）→inpatient 转科 API→未执行执行单 ward_id 重定向（计划时间不变，bed_no 落
 * toBedId 文本承载）+投影归属变更；转科停嘱联动长期行 CANCELLED（确定性终态）；转科后新长期
 * 医嘱执行单五环节收口→M04 计划回签 EXECUTED+重复 finish 幂等（第二次 409 NS-1021，对账锚
 * 稳定不双签）。
 *
 * <p><b>重定向断言载体裁决（转科三分规则实况）</b>：跨病区转科自动停嘱全部长期医嘱（转出
 * 长期行→order.stopped→nursing 未执行行 CANCELLED），长期行终态恒为 CANCELLED（消费顺序仅
 * 影响 ward_id 中间态）——确定性重定向断言锚=临时（STAT）医嘱快照执行单行（不在停嘱面，经
 * visit.transferred 消费重定向至转入病区，计划时间不动）；长期行 CANCELLED 为停嘱联动互补断言。
 *
 * <p>容器三件套类级独占（GC9 红线，InpatientTransferDischargeIT :71-79 逐字同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExecutionRedirectReconcileIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（流水键/幂等前置键载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（住院事件族消费链载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 转出/转入病区与床位（SQL 直插主数据） */
    private static final String WARD_FROM = "W-IT-9009";

    private static final String WARD_TO = "W-IT-9010";

    private static final long BED_FROM_ID = 920911L;

    private static final long BED_TO_ID = 920921L;

    private static final long PATIENT_ID = 920901L;

    /** 护理类项目码（NURSING 型医嘱计价面——billing 建项定价） */
    private static final String NURSING_ITEM_CODE = "IT-REDIR-NUR-001";

    /** V704 演示医师（开单操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** MQ 消费链路等待上限 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String adminToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String statOrderNo = "";

    private static String longOrderNo = "";

    /** STAT 快照执行单号（重定向断言锚） */
    private static String statExecutionNo = "";

    /** 重定向前快照行计划时点（计划时间不变断言锚） */
    private static OffsetDateTime statPlanTime;

    /** 转科后新长期医嘱（回签对账驱动载体） */
    private static String postTransferOrderNo = "";

    /** 回签驱动执行单号（m04_plan_no 非空） */
    private static String reconcileExecutionNo = "";

    /** 回签驱动 M04 计划号 */
    private static String reconcilePlanNo = "";

    /** 日切分解服务（次日计划预置——order-plan.generated 事件源） */
    @Autowired
    private IOrderPlanService orderPlanService;

    /**
     * 捕获队列声明（A.5-4 治理红线：经 MessagingGovernance 声明，"it" 消费者模块形态）：
     * nursing.order-execution.completed（id 64——回签对账辅路径断言面）。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_EXECUTION_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED;

        static final List<EventEnvelope> EXECUTION_COMPLETED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itRedirectCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_ORDER_EXECUTION_COMPLETED));
        }

        @Bean
        ItRedirectCaptureListener itRedirectCaptureListener(EventEnvelopeCodec codec) {
            return new ItRedirectCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（回执帧收列，不登记 received_event）。 */
    static class ItRedirectCaptureListener {

        private final EventEnvelopeCodec codec;

        ItRedirectCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = ItCaptureConfig.Q_EXECUTION_COMPLETED)
        void onMessage(Message message) {
            ItCaptureConfig.EXECUTION_COMPLETED.add(
                    codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8)));
        }
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
                .put("itemName", "IT 重定向项目 " + itemCode)
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

    /** 开立一条 NURSING 型医嘱（非用药行免剂量三要素，自动过审面）。 */
    private JsonNode createOrder(String orderClass, String freqCode) {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "NURSING").put("orderClass", orderClass);
        if (freqCode != null) {
            order.put("freqCode", freqCode);
        }
        order.putArray("items")
                .addObject()
                .put("itemType", "NURSING")
                .put("itemCode", NURSING_ITEM_CODE)
                .put("itemName", "IT 重定向护理项")
                .put("quantity", "1");
        return postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order);
    }

    /** 转抄核对放行（单人批链）。 */
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

    /** 该医嘱执行单行总数。 */
    private int executionRows(String m04OrderNo) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND deleted = 0",
                Integer.class,
                m04OrderNo);
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

    @Test
    @Order(1)
    @DisplayName("前置：登录/定价/患者两床两病区直插/入院链四步至 ADMITTED（W1）")
    void prepareAndAdmit() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        newItemWithPrice(NURSING_ITEM_CODE, 2000);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 重定向患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, 'IT9-91', ?, 'NORMAL', NULL, NULL, 'FREE'),"
                        + " (?, 'IT9-92', ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_FROM_ID,
                WARD_FROM,
                BED_TO_ID,
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
        admit.put("wardId", WARD_FROM).put("bedId", BED_FROM_ID).put("nursingLevel", "NORMAL");
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
    @DisplayName("长期+临时医嘱执行单生成：转抄落 STAT 快照行与 LONG 快照行，次日计划补 LONG 计划行")
    void generateExecutionsBeforeTransfer() {
        JsonNode statOrder = createOrder("STAT", null);
        statOrderNo = statOrder.path("orderNo").asText();
        JsonNode longOrder = createOrder("LONG", "qd");
        longOrderNo = longOrder.path("orderNo").asText();
        assertThat(statOrder.path("status").asText()).as("非用药类自动过审").isEqualTo("AUDITED");
        assertThat(longOrder.path("status").asText()).isEqualTo("AUDITED");
        transferCheck(List.of(statOrderNo, longOrderNo));
        awaitUntil("STAT 快照执行单落位", () -> executionRows(statOrderNo) >= 1);
        // 次日计划预置（日切服务直调等价 02:30 任务面）——LONG 计划行经 order-plan.generated 落位
        int created = orderPlanService.decomposeNextDay(
                LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1));
        assertThat(created).as("长期 qd 次日计划应生成一行").isEqualTo(1);
        awaitUntil("LONG 快照+计划执行单落位", () -> executionRows(longOrderNo) >= 2);
        // 重定向断言锚：STAT 快照行（唯一行）与计划时点冻结
        Map<String, Object> statRow = jdbcTemplate.queryForMap(
                "SELECT execution_no, plan_time, ward_id FROM nursing.order_execution"
                        + " WHERE m04_order_no = ? AND deleted = 0",
                statOrderNo);
        statExecutionNo = (String) statRow.get("execution_no");
        statPlanTime = ((Timestamp) statRow.get("plan_time")).toInstant().atOffset(ZoneOffset.UTC);
        assertThat(statRow.get("ward_id")).as("生成时归属=转出病区").isEqualTo(WARD_FROM);
    }

    @Test
    @Order(3)
    @DisplayName("转科 API：STAT 快照行重定向（ward 切换+bed_no=toBedId 文本+计划时间不变）+投影归属变更+LONG 行停嘱撤销")
    void transferRedirectsStatExecutionAndCancelsLongRows() {
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

        // 断言①：STAT 快照执行单重定向（未执行态随患者切新病区，计划时间不动）
        awaitUntil(
                "STAT 快照行重定向至转入病区",
                () -> WARD_TO.equals(jdbcTemplate.queryForObject(
                        "SELECT ward_id FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        statExecutionNo)));
        Map<String, Object> redirected = jdbcTemplate.queryForMap(
                "SELECT ward_id, bed_no, status, plan_time FROM nursing.order_execution WHERE execution_no = ?",
                statExecutionNo);
        assertThat(redirected.get("bed_no"))
                .as("bed_no 落 toBedId 文本承载（V800 id 49 载荷无床号口径）")
                .isEqualTo(String.valueOf(BED_TO_ID));
        assertThat(redirected.get("status")).as("重定向不迁状态（未执行 CREATED 保持）").isEqualTo("CREATED");
        assertThat(((Timestamp) redirected.get("plan_time")).toInstant())
                .as("计划时间不变（重定向只切归属）")
                .isEqualTo(statPlanTime.toInstant());

        // 断言②：投影归属变更（transferred 消费：ward 切换+床号补齐）
        awaitUntil(
                "投影归属切至转入病区",
                () -> WARD_TO.equals(jdbcTemplate.queryForObject(
                        "SELECT ward_id FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0",
                        String.class,
                        visitId)));

        // 断言③：转科停嘱联动——LONG 行全部 CANCELLED（转出长期医嘱自动停嘱确定性终态）
        int longRows = executionRows(longOrderNo);
        awaitUntil(
                "LONG 行全量 CANCELLED（停嘱联动）",
                () -> longRows
                        == jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND status = 'CANCELLED' AND deleted = 0",
                                Integer.class,
                                longOrderNo));
    }

    @Test
    @Order(4)
    @DisplayName("转科后回签对账：新长期医嘱计划行五环节→M04 计划 EXECUTED+confirm_status CONFIRMED+回执帧")
    void postTransferExecutionReconcilesM04Plan() {
        postTransferOrderNo = createOrder("LONG", "qd").path("orderNo").asText();
        transferCheck(List.of(postTransferOrderNo));
        int created = orderPlanService.decomposeNextDay(
                LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1));
        assertThat(created).as("转科后新长期医嘱次日计划一行").isEqualTo(1);
        awaitUntil(
                "计划执行单落位",
                () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND m04_plan_no IS NOT NULL AND deleted = 0",
                                Integer.class,
                                postTransferOrderNo)
                        >= 1);
        Map<String, Object> planRow = jdbcTemplate.queryForMap(
                "SELECT execution_no, m04_plan_no, ward_id FROM nursing.order_execution"
                        + " WHERE m04_order_no = ? AND m04_plan_no IS NOT NULL AND deleted = 0"
                        + " ORDER BY plan_time DESC LIMIT 1",
                postTransferOrderNo);
        reconcileExecutionNo = (String) planRow.get("execution_no");
        reconcilePlanNo = (String) planRow.get("m04_plan_no");
        assertThat(planRow.get("ward_id")).as("转科后新单归属=转入病区").isEqualTo(WARD_TO);

        // 五环节收口：人工补签收→腕带核对→开始（破码时间窗）→完成
        assertThat(postForEntity(
                                "/api/v1/nursing/executions/" + reconcileExecutionNo + "/sign-receive",
                                adminToken,
                                objectMapper.createObjectNode().put("receivedNote", "IT 重定向对账补签收"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        ObjectNode checkReq = objectMapper.createObjectNode();
        checkReq.put("code", visitId).put("codeType", "WRISTBAND");
        assertThat(postForEntity("/api/v1/nursing/executions/" + reconcileExecutionNo + "/check", adminToken, checkReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        ObjectNode startReq = objectMapper.createObjectNode();
        startReq.put("executorId", 66).put("overrideTimeWindow", true);
        assertThat(postForEntity("/api/v1/nursing/executions/" + reconcileExecutionNo + "/start", adminToken, startReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/nursing/executions/" + reconcileExecutionNo + "/finish",
                                adminToken,
                                objectMapper
                                        .createObjectNode()
                                        .put("executorId", 66)
                                        .put("routeCheckResult", "IT 重定向对账给药核对无误"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 主路径回签：M04 计划 EXECUTED+对账 CONFIRMED
        awaitUntil("M04 计划回签 EXECUTED", () -> "EXECUTED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.order_execute_plan WHERE plan_no = ?",
                        String.class,
                        reconcilePlanNo)));
        awaitUntil("回签对账 CONFIRMED", () -> "CONFIRMED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT confirm_status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        reconcileExecutionNo)));
        // 辅路径：回执帧可观测（id 64 载荷锚定）
        awaitUntil("回执帧送达捕获队列", () -> ItCaptureConfig.EXECUTION_COMPLETED.stream()
                .anyMatch(e -> reconcileExecutionNo.equals(
                        e.payload().path("executionNo").asText())));
        EventEnvelope receipt = ItCaptureConfig.EXECUTION_COMPLETED.stream()
                .filter(e -> reconcileExecutionNo.equals(
                        e.payload().path("executionNo").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(receipt.payload().path("m04PlanNo").asText()).isEqualTo(reconcilePlanNo);
        assertThat(receipt.payload().path("m04OrderNo").asText()).isEqualTo(postTransferOrderNo);
    }

    @Test
    @Order(5)
    @DisplayName("重复回签幂等：二次 finish 409 NS-1021，M04 计划/对账锚稳定且回执帧不二发")
    void secondFinishIsIdempotent() {
        int framesBefore = ItCaptureConfig.EXECUTION_COMPLETED.size();
        ResponseEntity<String> second = postForEntity(
                "/api/v1/nursing/executions/" + reconcileExecutionNo + "/finish",
                adminToken,
                objectMapper.createObjectNode().put("executorId", 66));
        assertThat(second.getStatusCode().value()).as("已完成执行单二次完成应 409").isEqualTo(409);
        assertThat(toNode(second.getBody()).path("errorCode").asText()).isEqualTo("NS-1021");
        // 第二次对账一致：计划仍 EXECUTED、对账仍 CONFIRMED、回执帧不重复（覆盖残余窗口）
        try {
            Thread.sleep(1_500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.order_execute_plan WHERE plan_no = ?",
                        String.class,
                        reconcilePlanNo))
                .as("二次 finish 后 M04 计划仍 EXECUTED（对账稳定）")
                .isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT confirm_status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        reconcileExecutionNo))
                .as("对账状态仍 CONFIRMED")
                .isEqualTo("CONFIRMED");
        assertThat(ItCaptureConfig.EXECUTION_COMPLETED.size())
                .as("回执帧不二发（CAS 前置拦截）")
                .isEqualTo(framesBefore);
    }
}

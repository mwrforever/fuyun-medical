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
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * PR-5 验收锚点①：门诊主流程直线段真栈（HTTP/MQ/DB 零 mock，裁决 0 演示终点全链）——
 * 排班模板造数+放号→窗口挂号（同事务签发 visit+visit.registered 真实发布）→分诊报到→叫号
 * →接诊→医生站开单（order.created 真实发布→billing PENDING 费用）→开方衔接（RX_REF 引用行
 * 登记+药品费用）→现金结算（settlement.completed→单据精确放行→真实 order.charged 扇出→
 * 处方 PENDING_DISPENSE）→配药/凭证核对/发药签名→诊毕（visit.finished）→诊毕后开单拒。
 * 资金动作全部经 billing 通道承载（裁决 7），本链零注入帧——订单/处方/放行三段均为生产发布器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutpatientFullFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（容器禁收敛入基类——P1-1 裁决，BillingSettlementFlowIT :62-78 逐字同型；
     *  @DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载，本类不重复声明） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（号源池键/流水键/候诊 ZSET） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（全链事件总线；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 收费项目码（brief 冻结字面量：检查检验两枚；药品与项目共码=计费行映射依据） */
    private static final String LAB_ITEM_CODE = "LAB-001";

    private static final String DRUG_ITEM_CODE = "DRUG-001";

    /** 本 IT 开诊科室（排班/限购/队列谓词维度） */
    private static final String DEPT_CODE = "DEP-IT-FLOW";

    /** 排班模板造数行主键（schedule_template SQL 直插——brief 造数口径） */
    private static final long TEMPLATE_ID = 910901L;

    /** 门诊患者主索引（patient.patient SQL 直插，resolve 仅需主档行） */
    private static final long PATIENT_ID = 910951L;

    /** 批次账造数行主键（V703 零数据行——IT 经 jdbcTemplate 直插，偏差⑧红线） */
    private static final long BATCH_ID = 910961L;

    /** V704 演示医师（sys_user id=3 与 employee_id 同值——Task 2 身份链口径；开单/开方操作者） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    private static String adminToken = "";
    private static String reviewerToken = "";
    private static String doctorToken = "";

    /**
     * CF-3 当日首位就诊号冻结形态（O+今日+00001——容器独占 Redis 流水键自 1 起签发）。日期段取
     * 系统默认时区 {@code LocalDate.now()}：与生成器 VisitIdIssuerImpl.issue 的流水键日期戳同源
     * （其取值无时区参），亦与本文件造数 endDate/池定位同源——原 UTC 取值在 UTC+8 每日 00:00-08:00
     * 与生成器错日分叉致假红窗。
     */
    private static final String EXPECTED_VISIT_ID =
            "O" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "00001";

    /** 跨用例链路状态（JUnit 每用例新实例，业务号/单据锚经 static 传递） */
    private static long poolId;

    private static long drugId;
    private static String apptNo = "";
    private static String visitId = "";
    private static String orderNo = "";
    private static String rxNo = "";
    private static String dispenseNo = "";
    private static String prescriptionItemId = "";
    private static String settleNo = "";

    /**
     * 捕获队列声明（A.5-4 治理红线：禁止测试自声明交换机/裸队列——经 MessagingGovernance 声明，
     * BillingSettlementFlowIT ItCaptureConfig :121-148 同型）。一事件一队列：
     * q.it.outpatient.visit.registered / q.it.outpatient.visit.finished（V204 id 32/33 登记行）。
     * 声明副作用 registerSubscriber 会把 "it" 追加进 subscriber_modules——「it」作消费方模块标识
     * 仅进订阅清单不改登记行，与 billing 先例同款；raw 解析监听不进幂等台账。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_VISIT_REGISTERED =
                MessagingConstants.QUEUE_PREFIX + "it." + OutpatientMessagingConstants.EVENT_VISIT_REGISTERED;

        static final String Q_VISIT_FINISHED =
                MessagingConstants.QUEUE_PREFIX + "it." + OutpatientMessagingConstants.EVENT_VISIT_FINISHED;

        static final CountDownLatch REGISTERED_LATCH = new CountDownLatch(1);

        static final CountDownLatch FINISHED_LATCH = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itVisitRegisteredCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", OutpatientMessagingConstants.EVENT_VISIT_REGISTERED));
        }

        @Bean
        Declarables itVisitFinishedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", OutpatientMessagingConstants.EVENT_VISIT_FINISHED));
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
        @RabbitListener(queues = {ItCaptureConfig.Q_VISIT_REGISTERED, ItCaptureConfig.Q_VISIT_FINISHED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            if (OutpatientMessagingConstants.EVENT_VISIT_REGISTERED.equals(envelope.eventType())) {
                ItCaptureConfig.REGISTERED_LATCH.countDown();
            } else if (OutpatientMessagingConstants.EVENT_VISIT_FINISHED.equals(envelope.eventType())) {
                ItCaptureConfig.FINISHED_LATCH.countDown();
            }
        }
    }

    /** 带 Bearer 的 GET 助手（BillingSettlementFlowIT :173-180 同型）。 */
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

    /** POST JSON 助手（带 Content-Type/Bearer；状态码断言场景改用 postForEntity）。 */
    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体 errorCode 断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 无体 POST 助手（admit/verify/issue 等 void 端点的状态码断言面）。 */
    private ResponseEntity<String> postEmpty(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
    }

    /** 建项目+定价+发布一步到位（BillingSettlementFlowIT :319-349 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 全链项目 " + itemCode)
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
                .put(
                        "effectiveFrom",
                        LocalDate.now(ZoneOffset.UTC)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString())
                .put("priceSource", "OFFICIAL_DOC");
        long priceRowId = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + priceRowId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
    }

    /** 轮询等待该就诊 PENDING 费用行金额到位（真实发布事件驱动异步，上限 10s），返回该行状态。 */
    private Map<String, Object> awaitPendingFee(String visitIdText, String sourceRef, String trigger, long amountFen)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, status, amount, trigger_point, source_ref FROM billing.fee_record"
                            + " WHERE visit_id = ? AND source_ref = ? AND trigger_point = ? AND amount = ?",
                    visitIdText,
                    sourceRef,
                    trigger,
                    amountFen);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：visit=" + visitIdText + "，sourceRef=" + sourceRef);
    }

    /** 读 visit 行状态与时间列（SQL 直读；状态机迁移与国标时间采集断言共用）。 */
    private Map<String, Object> visitRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, admitted_at, finished_at, disposition FROM outpatient.visit WHERE visit_id = ?",
                visitId);
    }

    /** 轮询等待 visit 到达目标状态（回执异步推进收敛，上限 10s，禁盲等）。 */
    private void awaitVisitStatus(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(visitRow().get("status"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("visit 状态迁移超时：期望 " + expected);
    }

    /** 读申请单当前状态（SQL 直读）。 */
    private String orderStatus(String orderNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outpatient.clinic_order WHERE order_no = ?", String.class, orderNoText);
    }

    /** 轮询等待申请单到达目标状态（放行异步收敛，上限 10s，禁盲等）。 */
    private void awaitOrderStatus(String orderNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(orderStatus(orderNoText))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("申请单状态迁移超时：orderNo=" + orderNoText + "，期望 " + expected);
    }

    /** 读处方当前状态（SQL 直读）。 */
    private String rxStatus(String rxNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.prescription WHERE rx_no = ?", String.class, rxNoText);
    }

    /** 轮询等待处方到达目标状态（放行异步收敛，上限 10s，禁盲等）。 */
    private void awaitRxStatus(String rxNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(rxStatus(rxNoText))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("处方状态迁移超时：rxNo=" + rxNoText + "，期望 " + expected);
    }

    /** 读 RX_REF 引用行发药回流镜像（pharmacy 回执派生展示面）。 */
    private String rxRefMirror() {
        return jdbcTemplate.queryForObject(
                "SELECT dispense_status FROM outpatient.clinic_order WHERE ext_ref = ? AND order_type = 'RX_REF'",
                String.class,
                rxNo);
    }

    /** 轮询等待镜像到达目标值（发药回流异步收敛，上限 10s，禁盲等）。 */
    private void awaitRxRefMirror(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(rxRefMirror())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("RX_REF 镜像迁移超时：期望 " + expected);
    }

    /** 读批次账数值列（列名仅取本类固定字面量 quantity/locked_qty，禁外部拼接）。 */
    private long batchField(String column) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM pharmacy.drug_batch WHERE id = ?", Long.class, BATCH_ID);
        return v == null ? -1 : v;
    }

    @Test
    @Order(1)
    @DisplayName("前置：三账号登录；两项目（LAB-001 3000 分/DRUG-001 2000 分）+药品+批次+排班模板直插放号；V705 字典 19 条在位")
    void prepareMasterData() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);

        newItemWithPrice(LAB_ITEM_CODE, 3000);
        newItemWithPrice(DRUG_ITEM_CODE, 2000);

        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", DRUG_ITEM_CODE)
                .put("genericName", "IT 全链药品")
                .put("dosageForm", "片剂")
                .put("specification", "0.5g×50")
                .put("unit", "盒")
                .putArray("routeCodes")
                .add("ORAL");
        drug.put("essentialFlag", false)
                .put("antibioClass", "NONE")
                .put("hazardLevel", "NONE")
                .put("skinTestFlag", false)
                .put("narcoticClass", "NORMAL")
                .put("itemCode", DRUG_ITEM_CODE);
        drugId = postJson("/api/v1/pharmacy/drugs", adminToken, drug).path("id").asLong();
        jdbcTemplate.update(
                "INSERT INTO pharmacy.drug_batch (id, drug_id, storehouse, batch_no, production_date,"
                        + " expire_date, quantity, locked_qty, status)"
                        + " VALUES (?, ?, 'OUTP_PHARM', 'B-ITF-01', ?, ?, 50, 0, 'IN_STOCK')",
                BATCH_ID,
                drugId,
                java.sql.Date.valueOf("2025-06-01"),
                java.sql.Date.valueOf("2027-06-01"));
        jdbcTemplate.update(
                "INSERT INTO outpatient.schedule_template (id, dept_code, doctor_id, eff_from, eff_to,"
                        + " week_pattern, session, appt_type, slot_start, slot_end, slot_quota, room,"
                        + " release_days, release_time, status)"
                        + " VALUES (?, ?, '3', CURRENT_DATE, NULL, '1111111', 'MORNING', 'GENERAL',"
                        + " TIME '08:00', TIME '12:00', 5, 'IT-ROOM-F1', 1, TIME '07:00', 'ACTIVE')",
                TEMPLATE_ID,
                DEPT_CODE);
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("endDate", LocalDate.now().toString()).put("days", 1);
        assertThat(postJson("/api/v1/outpatient/schedules/generate", adminToken, generate)
                        .asInt())
                .as("当日窗口×单模板应生成一行排班")
                .isEqualTo(1);
        poolId = jdbcTemplate.queryForObject(
                "SELECT p.id FROM outpatient.appt_number_pool p"
                        + " JOIN outpatient.schedule s ON s.id = p.schedule_id"
                        + " WHERE s.sched_date = CURRENT_DATE AND p.deleted = 0 AND s.deleted = 0"
                        + " ORDER BY p.id LIMIT 1",
                Long.class);
        assertThat(poolId).as("当日号源池行应在位").isNotNull();

        // V705 门诊字典在位断言（brief 冻结计数：就诊类型 6+离院去向 8+号别 5=19 条）
        Integer dictItems = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM system.dict_item WHERE dict_version_id IN (3, 4, 5)", Integer.class);
        assertThat(dictItems).as("V705 三类字典条目应共 19 条").isEqualTo(19);
    }

    @Test
    @Order(2)
    @DisplayName("窗口挂号：POST /appointments 一步 TAKEN，同事务签发 visit_id=O+今日+00001，visit.registered 帧在位")
    void windowRegistrationIssuesVisitAndPublishesRegistered() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "全链患者");
        ObjectNode book = objectMapper.createObjectNode();
        book.put("patientId", PATIENT_ID).put("poolId", poolId).put("channel", "WINDOW");
        JsonNode booked = postJson("/api/v1/outpatient/appointments", adminToken, book);
        apptNo = booked.path("apptNo").asText();
        visitId = booked.path("visitId").asText();
        assertThat(booked.path("status").asText()).as("窗口挂号一步直达 TAKEN").isEqualTo("TAKEN");
        assertThat(apptNo).as("预约单号契约 AP+yyyyMMdd+6 位").matches("AP\\d{14}");
        assertThat(visitId).as("CF-3 当日首位就诊号冻结形态").isEqualTo(EXPECTED_VISIT_ID);

        // visit.registered 真实发布帧在位：信封生产者与载荷五组件勾稽（V204 id 32 契约面）
        assertThat(ItCaptureConfig.REGISTERED_LATCH.await(10, TimeUnit.SECONDS))
                .as("outpatient.visit.registered 事件应可消费")
                .isTrue();
        EventEnvelope registered = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> OutpatientMessagingConstants.EVENT_VISIT_REGISTERED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(registered.producer()).isEqualTo(OutpatientMessagingConstants.MODULE);
        assertThat(registered.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(registered.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(registered.payload().path("visitType").asText()).isEqualTo("GENERAL");
        assertThat(registered.payload().path("deptCode").asText()).isEqualTo(DEPT_CODE);
        assertThat(registered.payload().path("doctorId").asText()).isEqualTo("3");
    }

    @Test
    @Order(3)
    @DisplayName("报到→叫号→接诊：票 WAITING→CALLED 与 REST 快照首行票号一致，admit 后 IN_CONSULT+admitted_at 非空")
    void checkInAndCallTicket() throws Exception {
        ObjectNode checkIn = objectMapper.createObjectNode();
        checkIn.put("visitId", visitId).put("stationId", "IT-STATION-F1");
        JsonNode ticket = postJson("/api/v1/outpatient/triage/check-in", adminToken, checkIn);
        assertThat(ticket.path("status").asText()).as("报到建票初始候诊态").isEqualTo("WAITING");
        String ticketNo = ticket.path("ticketNo").asText();
        assertThat(ticketNo).as("票号契约 A+队列内当日序").matches("A\\d{3}");

        // REST 快照双通道之一：候诊首行与报到票号一致
        JsonNode waitingSnapshot =
                getJson("/api/v1/outpatient/queues/" + DEPT_CODE + "/tickets?status=WAITING", adminToken);
        assertThat(waitingSnapshot.get(0).path("ticketNo").asText()).isEqualTo(ticketNo);

        // 叫号（ZSET 原子出队）→ 已叫态与 REST 快照首行票号一致
        ObjectNode call = objectMapper.createObjectNode();
        call.put("deptCode", DEPT_CODE).put("doctorId", "3");
        JsonNode called = postJson("/api/v1/outpatient/queue/call", adminToken, call);
        assertThat(called.path("status").asText()).as("叫号后票据已叫态").isEqualTo("CALLED");
        assertThat(called.path("ticketNo").asText()).isEqualTo(ticketNo);
        JsonNode calledSnapshot =
                getJson("/api/v1/outpatient/queues/" + DEPT_CODE + "/tickets?status=CALLED", adminToken);
        assertThat(calledSnapshot.get(0).path("ticketNo").asText())
                .as("已叫态快照首行票号应与叫号回执一致")
                .isEqualTo(ticketNo);

        // 接诊（叫号≠接诊）：visit IN_CONSULT+admitted_at 回填（库端 now()，Task 8 库端时钟合并 CAS）
        assertThat(postEmpty("/api/v1/outpatient/visits/" + visitId + "/admit", adminToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(visitRow().get("status")).as("接诊后就诊中").isEqualTo("IN_CONSULT");
        assertThat(visitRow().get("admitted_at")).as("接诊时间国标采集应回填").isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName(
            "医生站开单（LAB-001×2）→ order.created 真实发布 → billing PENDING 费用（ORDER_CONFIRMED/sourceRef=orderNo/6000 分）+visit 推进待缴费")
    void createOrderGeneratesPendingFeesViaRealPublisher() throws Exception {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "LAB");
        ObjectNode line = order.putArray("items").addObject();
        line.put("itemCode", LAB_ITEM_CODE).put("quantity", "2").put("usageSummary", "IT 血常规复查");
        JsonNode vo = postJson("/api/v1/outpatient/visits/" + visitId + "/orders", doctorToken, order);
        orderNo = vo.path("orderNo").asText();
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");
        assertThat(orderNo).as("申请单号契约 OP+yyyyMMdd+6 位").matches("OP\\d{14}");

        // 真实发布链（非注入）：billing 消费 order.created 展开计费行——金额服务端按快照算 3000×2
        Map<String, Object> feeRow = awaitPendingFee(visitId, orderNo, "ORDER_CONFIRMED", 6000);
        assertThat(feeRow.get("status")).isEqualTo("PENDING");

        // 缴费回执推进：申请单 CREATED→PENDING_FEE+visit IN_CONSULT→PENDING_FEE（每迁必记）
        awaitOrderStatus(orderNo, "PENDING_FEE");
        awaitVisitStatus("PENDING_FEE");
    }

    @Test
    @Order(5)
    @DisplayName("开方衔接（doctordemo 持权经 practice/check 放行）：RX_REF 引用行 ext_ref=rxNo 零明细行+药品费用 PENDING 4000 分")
    void openPrescriptionRegistersRxRefAndGeneratesDrugFees() throws Exception {
        ObjectNode rxReq = objectMapper.createObjectNode();
        rxReq.put("rxType", "OUTPATIENT");
        ObjectNode line = rxReq.putArray("items").addObject();
        line.put("drugId", drugId)
                .put("quantity", "2")
                .put("routeCode", "ORAL")
                .put("frequency", "TID")
                .put("days", 3)
                .put("singleDose", "0.5g");
        JsonNode vo = postJson("/api/v1/outpatient/visits/" + visitId + "/prescriptions", doctorToken, rxReq);
        rxNo = vo.path("extRef").asText();
        assertThat(vo.path("orderType").asText()).as("衔接动作直出 RX_REF 引用行").isEqualTo("RX_REF");
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");
        assertThat(rxNo).as("处方号契约 R+yyyyMMdd+6 位").matches("R\\d{14}");
        assertThat(vo.path("items").isEmpty()).as("引用行不复制药品明细（红线 3）").isTrue();

        // 本域登记面：ext_ref=rxNo 引用行在账且零明细行（M-4 零双头）
        Map<String, Object> rxRefRow = jdbcTemplate.queryForMap(
                "SELECT status, ext_ref FROM outpatient.clinic_order WHERE ext_ref = ? AND order_type = 'RX_REF'",
                rxNo);
        assertThat(rxRefRow.get("status")).isEqualTo("CREATED");
        Integer refItemRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outpatient.clinic_order_item oi"
                        + " JOIN outpatient.clinic_order o ON o.id = oi.order_id"
                        + " WHERE o.ext_ref = ? AND o.order_type = 'RX_REF'",
                Integer.class,
                rxNo);
        assertThat(refItemRows).isZero();

        // pharmacy.prescription.created 真实链：billing 生成药品费用（2000 分×2，引用行不产生计费行）
        awaitPendingFee(visitId, rxNo, "PRESCRIPTION_EFFECTIVE", 4000);
    }

    @Test
    @Order(6)
    @DisplayName("现金结算：preview 10000 分勾稽→settle CASH→两单 CHARGED+处方 PENDING_DISPENSE（真实 order.charged 精确放行）+visit 回就诊中")
    void settleVisitFeesAndFanOutCharged() throws Exception {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", visitId).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong()).as("预结算总额=检查 6000+药品 4000").isEqualTo(10000L);
        settleNo = pv.path("settleNo").asText();
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", settleNo);
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "10000");
        JsonNode sv = postJson("/api/v1/billing/settlements", adminToken, settle);
        assertThat(sv.path("status").asText()).isEqualTo("SETTLED");

        // 单据精确放行（settlement.completed 反查清单）：申请单与 RX_REF 引用行同事务 CHARGED
        awaitOrderStatus(orderNo, "CHARGED");
        awaitOrderStatus(rxRefOrderNo(), "CHARGED");

        // 真实 order.charged 扇出（PR-4 注入帧通道废止的回切实证）：处方 PENDING_DISPENSE+发药单入队
        awaitRxStatus(rxNo, "PENDING_DISPENSE");
        awaitDispenseQueued();

        // 回诊推进（PENDING_FEE→IN_CONSULT 合法迁移对，红线 5）
        awaitVisitStatus("IN_CONSULT");
    }

    /** RX_REF 引用行业务号（ext_ref=rxNo 反查，放行终态断言锚）。 */
    private String rxRefOrderNo() {
        return jdbcTemplate.queryForObject(
                "SELECT order_no FROM outpatient.clinic_order WHERE ext_ref = ? AND order_type = 'RX_REF'",
                String.class,
                rxNo);
    }

    /** 轮询等待处方 PENDING_DISPENSE 且发药单 CREATED（放行异步建单，上限 10s）。 */
    private void awaitDispenseQueued() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer released = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pharmacy.prescription p"
                            + " JOIN pharmacy.dispense d ON d.rx_no = p.rx_no AND d.deleted = 0"
                            + " WHERE p.rx_no = ? AND p.status = 'PENDING_DISPENSE' AND d.status = 'CREATED'",
                    Integer.class,
                    rxNo);
            if (released != null && released == 1) {
                JsonNode array = getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNo, adminToken);
                if (array.isArray() && array.size() == 1) {
                    dispenseNo = array.get(0).path("dispenseNo").asText();
                    prescriptionItemId = array.get(0)
                            .path("items")
                            .get(0)
                            .path("prescriptionItemId")
                            .asText();
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("charged 放行入队超时：rxNo=" + rxNo);
    }

    @Test
    @Order(7)
    @DisplayName("发药三段携凭证核对：pick 追溯码采集→verify 携 settlementNo 归属一致（PH-1018 核验通道）→issue 后 RX_REF 镜像 DISPENSED")
    void dispenseThroughVerifyWithCredential() throws Exception {
        // 配药（admin 调配位）：FEFO 选批锁定+追溯码逐盒采集
        ObjectNode pick = objectMapper.createObjectNode();
        ObjectNode pickLine = pick.putArray("items").addObject();
        pickLine.put("prescriptionItemId", prescriptionItemId);
        pickLine.putArray("traceCodes").add("TR-ITF-001").add("TR-ITF-002");
        assertThat(postForEntity("/api/v1/pharmacy/dispenses/" + dispenseNo + "/pick", adminToken, pick)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(batchField("locked_qty")).as("配药锁定 2 盒").isEqualTo(2L);

        // 核对（reviewer 与调配人互异）携取药凭证=settlementNo：settledUnder 反查归属一致放行
        // （Task 11 verify 可选 body 通道的端到端收口——核对人与凭证核验双守卫一次通过）
        ObjectNode verifyBody = objectMapper.createObjectNode();
        verifyBody.put("credential", settleNo);
        assertThat(postForEntity("/api/v1/pharmacy/dispenses/" + dispenseNo + "/verify", reviewerToken, verifyBody)
                        .getStatusCode()
                        .is2xxSuccessful())
                .as("凭证与处方归属一致应放行核对")
                .isTrue();

        // 发药签名（reviewer）：批次锁定转扣减+出库流水同事务，处方转 DISPENSED
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNo + "/issue", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(rxStatus(rxNo)).isEqualTo("DISPENSED");
        assertThat(batchField("quantity")).as("批次 50 锁 2 发 2→48").isEqualTo(48L);
        assertThat(batchField("locked_qty")).isZero();

        // 发药完成回流（pharmacy.dispense.completed）：RX_REF 引用行镜像 DISPENSED（状态机五值不变）
        awaitRxRefMirror("DISPENSED");
    }

    @Test
    @Order(8)
    @DisplayName("诊毕：DISCHARGE_HOME→FINISHED+finished_at 回填+visit.finished 帧在位；诊毕后开单拒 409 OP-1011")
    void finishVisitWithDisposition() throws Exception {
        ObjectNode finish = objectMapper.createObjectNode();
        finish.put("disposition", "DISCHARGE_HOME");
        JsonNode vo = postJson("/api/v1/outpatient/visits/" + visitId + "/finish", doctorToken, finish);
        assertThat(vo.path("status").asText()).as("诊毕终态").isEqualTo("FINISHED");
        Map<String, Object> row = visitRow();
        assertThat(row.get("finished_at")).as("诊毕时间应回填").isNotNull();
        assertThat(row.get("disposition")).as("离院去向国标代码落库").isEqualTo("DISCHARGE_HOME");

        // visit.finished 真实发布帧在位（V204 id 33 契约面）
        assertThat(ItCaptureConfig.FINISHED_LATCH.await(10, TimeUnit.SECONDS))
                .as("outpatient.visit.finished 事件应可消费")
                .isTrue();
        EventEnvelope finished = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> OutpatientMessagingConstants.EVENT_VISIT_FINISHED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(finished.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(finished.payload().path("disposition").asText()).isEqualTo("DISCHARGE_HOME");

        // 终态红线：FINISHED 后拒绝一切开单动作（OP-1011，03 Spec 红线 5）
        ObjectNode lateOrder = objectMapper.createObjectNode();
        lateOrder.put("orderType", "LAB");
        lateOrder.putArray("items").addObject().put("itemCode", LAB_ITEM_CODE).put("quantity", "1");
        ResponseEntity<String> rejected =
                postForEntity("/api/v1/outpatient/visits/" + visitId + "/orders", doctorToken, lateOrder);
        assertThat(rejected.getStatusCode().value()).as("诊毕后开单应 409").isEqualTo(409);
        assertThat(toNode(rejected.getBody()).path("errorCode").asText()).isEqualTo("OP-1011");
    }
}

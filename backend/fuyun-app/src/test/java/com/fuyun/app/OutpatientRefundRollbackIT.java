package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.properties.OutpatientProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * PR-5 验收锚点②：退号→退费→放行回滚真栈（HTTP/MQ/DB 零 mock）——
 * 已支付就诊（挂号费手工计费+检查单 A+处方 B 分结算缴费放行）→退药受理→免审退费 apply
 * （DAY_CORRECTION 落库即 APPROVED）→refund.approved 双面收敛：药品结算（orderRefs 空）
 * 零 order.cancelled 扇出且同 visit 检查单 A 保持 CHARGED（误伤面闭合，裁决 5/6 结算精确
 * 语义），检查单退费结算逐单扇出 order.cancelled 携 rxNos 精确清单；未支付 portal 预约退号
 * 免退费直取消（回池+appointment.cancelled feeRefundTriggered=false）；支付超时延迟档位
 * 到期 NO_SHOW 释放+爽约信用限约区间写入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutpatientRefundRollbackIT extends FuyunStackITBase {

    /** 类级独占三容器（容器禁收敛入基类——P1-1 裁决，BillingSettlementFlowIT :62-78 逐字同型；
     *  @DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（号源池键/占位键/单号流水键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（退款/退号/超时事件链；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /**
     * 测试域参数覆盖（brief 内在矛盾的测试面收口，零生产改动）：超时释放的触发面已按 stub 注入
     * 红线以合成信封走生产死信同一路由键承载（见 timeoutNoShowReleasesPoolAndRecordsCredit 注记
     * ——quorum 延迟队列惰性到期实证），brief「appointment-timeout=PT2S」于链路上已无功能位；
     * 且 appointmentTimeout 挂生产校验 @DurationMin(minutes=1)，PT2S 实例经
     * ConfigurationPropertiesBindingPostProcessor 绑定校验必拒（Bindable.ofInstance 以实例现值
     * 为绑定起点，属性源无关）。本 Bean 唯一收窄项=爽约阈值 1：使首笔 NO_SHOW 即写限约区间
     * （缺省阈值 3 时单笔不落 restrict 列，冻结断言「credit 行 restrict 写入」不可达）；其余
     * 参数与生产缺省逐值同源（@Primary 按类型注入必中，不受注册序影响）。
     */
    @TestConfiguration
    static class OutpatientTestPropsConfig {

        /** @Primary 按类型胜出：@EnableConfigurationProperties 注册的缺省 Bean 退位不参注入 */
        @Bean
        @org.springframework.context.annotation.Primary
        OutpatientProperties testOutpatientProperties() {
            return new OutpatientProperties(Duration.ofMinutes(15), 1, 90, 1, 90);
        }
    }

    /** 运行唯一项目码（每 IT 独占容器库，固定字面量与 brief 冻结值对齐） */
    private static final String REG_ITEM_CODE = "REG-001";

    private static final String LAB_ITEM_CODE = "LAB-001";

    private static final String DRUG_ITEM_CODE = "DRUG-001";

    /** 本 IT 开诊科室（排班/限购/队列谓词维度） */
    private static final String DEPT_CODE = "DEP-IT-REFUND";

    /** 排班模板造数行主键（schedule_template SQL 直插——brief 造数口径） */
    private static final long TEMPLATE_ID = 910601L;

    /** 主链患者（已支付就诊全链）/超时用例患者主索引（patient.patient SQL 直插） */
    private static final long PAID_PATIENT_ID = 910701L;

    private static final long TIMEOUT_PATIENT_ID = 910702L;

    /** portal 预约患者合成证件号（建档 API 通道，全周唯一） */
    private static final String PORTAL_ID_CARD = "110101199203210311";

    /** 批次账造数行主键（V703 零数据行——IT 经 jdbcTemplate 直插，偏差⑧红线） */
    private static final long BATCH_ID = 910801L;

    /** V704 演示医师登录名（sys_user id=3，PRESCRIPTION 执业授权——开单/开方操作者须持权医师） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    private static String adminToken = "";
    private static String reviewerToken = "";
    private static String doctorToken = "";

    /** 跨用例链路状态（JUnit 每用例新实例，业务号/单据锚经 static 传递） */
    private static long todayPoolId;

    private static long tomorrowPoolId;
    private static long drugId;
    private static String apptNo = "";
    private static String visitId = "";
    private static String orderNoA = "";
    private static String rxNo = "";
    private static String dispenseNo = "";
    private static String prescriptionItemId = "";
    private static long examSettlementId;
    private static long drugSettlementId;
    private static long examFeeId;
    private static long drugFeeId;

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 捕获队列声明（A.5-4 治理红线：禁止测试自声明交换机/裸队列——经 MessagingGovernance 声明，
     * BillingSettlementFlowIT ItCaptureConfig :121-148 同型）。一事件一队列：
     * q.it.billing.refund.approved / q.it.outpatient.order.cancelled / q.it.outpatient.appointment.cancelled。
     * 声明副作用 registerSubscriber 会把 "it" 追加进 subscriber_modules——「it」作消费方模块标识仅进
     * 订阅清单不改登记行，与 billing 先例同款；raw 解析监听不进幂等台账。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_REFUND_APPROVED =
                MessagingConstants.QUEUE_PREFIX + "it." + BillingMessagingConstants.EVENT_REFUND_APPROVED;

        static final String Q_ORDER_CANCELLED =
                MessagingConstants.QUEUE_PREFIX + "it." + OutpatientMessagingConstants.EVENT_ORDER_CANCELLED;

        static final String Q_APPT_CANCELLED =
                MessagingConstants.QUEUE_PREFIX + "it." + OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED;

        static final CountDownLatch REFUND_LATCH = new CountDownLatch(1);

        static final CountDownLatch ORDER_CANCELLED_LATCH = new CountDownLatch(1);

        static final CountDownLatch APPT_CANCELLED_LATCH = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itRefundApprovedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", BillingMessagingConstants.EVENT_REFUND_APPROVED));
        }

        @Bean
        Declarables itOrderCancelledCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", OutpatientMessagingConstants.EVENT_ORDER_CANCELLED));
        }

        @Bean
        Declarables itAppointmentCancelledCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED));
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
                queues = {
                    ItCaptureConfig.Q_REFUND_APPROVED,
                    ItCaptureConfig.Q_ORDER_CANCELLED,
                    ItCaptureConfig.Q_APPT_CANCELLED
                })
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            if (BillingMessagingConstants.EVENT_REFUND_APPROVED.equals(envelope.eventType())) {
                ItCaptureConfig.REFUND_LATCH.countDown();
            } else if (OutpatientMessagingConstants.EVENT_ORDER_CANCELLED.equals(envelope.eventType())) {
                ItCaptureConfig.ORDER_CANCELLED_LATCH.countDown();
            } else if (OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED.equals(envelope.eventType())) {
                ItCaptureConfig.APPT_CANCELLED_LATCH.countDown();
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

    /** POST JSON 助手（token 可空=匿名；状态码断言场景改用 postForEntity）。 */
    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 带令牌 POST（token 可空=匿名免登录通道；返回原始响应实体）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 无体 POST 助手（admit/issue 等 void 端点的状态码断言面）。 */
    private ResponseEntity<String> postEmpty(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
    }

    /** 建项目+定价+发布一步到位（BillingSettlementFlowIT :319-349 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 退费回滚项目 " + itemCode)
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
                        LocalDate.now()
                                .atStartOfDay()
                                .toInstant(java.time.ZoneOffset.UTC)
                                .toString())
                .put("priceSource", "OFFICIAL_DOC");
        long priceRowId = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + priceRowId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
    }

    /** 预结算+现金正式结算一步到位（Σpayments.amount==totalAmount 勾稽；返回结算单号）。 */
    private String settleVisit(String visitIdText, long expectTotalFen) {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PAID_PATIENT_ID).put("visitId", visitIdText).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong())
                .as("预结算总额应与在缴费用行勾稽：visit=%s", visitIdText)
                .isEqualTo(expectTotalFen);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", pv.path("settleNo").asText());
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", String.valueOf(expectTotalFen));
        JsonNode sv = postJson("/api/v1/billing/settlements", adminToken, settle);
        assertThat(sv.path("status").asText()).isEqualTo("SETTLED");
        return pv.path("settleNo").asText();
    }

    /** 轮询等待该就诊的 PENDING 费用行金额到位（事件驱动异步，上限 10s），返回费用行 id。 */
    private long awaitPendingFee(String sourceRef, String trigger, long amountFen) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM billing.fee_record WHERE source_ref = ? AND trigger_point = ?"
                            + " AND status = 'PENDING' AND amount = ?",
                    Long.class,
                    sourceRef,
                    trigger,
                    amountFen);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：sourceRef=" + sourceRef + "，trigger=" + trigger);
    }

    /** 读申请单当前状态（SQL 直读，轮询与终态断言共用）。 */
    private String orderStatus(String orderNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outpatient.clinic_order WHERE order_no = ?", String.class, orderNoText);
    }

    /** 轮询等待申请单到达目标状态（放行/逆向异步收敛，上限 10s，禁盲等）。 */
    private void awaitOrderStatus(String orderNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(orderStatus(orderNoText))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("申请单状态迁移超时：orderNo=" + orderNoText + "，期望 " + expected);
    }

    /** 读处方当前状态（SQL 直读，轮询与终态断言共用）。 */
    private String rxStatus(String rxNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.prescription WHERE rx_no = ?", String.class, rxNoText);
    }

    /** 轮询等待处方到达目标状态（跨服务异步链收敛等待，上限 10s，禁盲等）。 */
    private void awaitRxStatus(String rxNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(rxStatus(rxNoText))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("处方状态迁移超时：rxNo=" + rxNoText + "，期望 " + expected);
    }

    /** 读该处方号费用行的执行占用状态（billing 占用回写异步收敛的断言锚点）。 */
    private String occupancy(String rxNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT exec_occupy_status FROM billing.fee_record"
                        + " WHERE source_ref = ? AND trigger_point = 'PRESCRIPTION_EFFECTIVE'",
                String.class,
                rxNoText);
    }

    /** 轮询等待占用状态收敛到目标值（发药 DISPENSED / 全退 NONE，上限 10s，禁盲等）。 */
    private void awaitOccupancy(String rxNoText, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(occupancy(rxNoText))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("占用回写超时：rxNo=" + rxNoText + "，期望 " + expected);
    }

    /** 读批次账数值列（列名仅取本类固定字面量 quantity/locked_qty，禁外部拼接）。 */
    private long batchField(String column) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM pharmacy.drug_batch WHERE id = ?", Long.class, BATCH_ID);
        return v == null ? -1 : v;
    }

    /** 读池行已用号数（DB 权威库存，回池/占用终态断言锚）。 */
    private long poolUsedCount(long pool) {
        Long used = jdbcTemplate.queryForObject(
                "SELECT used_count FROM outpatient.appt_number_pool WHERE id = ?", Long.class, pool);
        return used == null ? -1 : used;
    }

    /** 号源池键拼装（fy:outpatient:pool:{poolId}，A.5-1；{poolId} 为 hash tag 字面量）。 */
    private static String poolKey(long pool) {
        return "fy:outpatient:pool:{" + pool + "}";
    }

    @Test
    @Order(1)
    @DisplayName("前置：三账号登录；项目/药品/批次/排班造数；窗口挂号→挂号费手工计费+缴费回填 PAID→接诊→检查单 A 与处方 B 分结算放行")
    void preparePaidVisitWithTwoDocs() throws Exception {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);

        // 造数基座：三项目（挂号费 1000/检查 3000/药品 2000 分）+ 药品档案 + 批次 50 + 排班模板（全周 quota 5）
        newItemWithPrice(REG_ITEM_CODE, 1000);
        newItemWithPrice(LAB_ITEM_CODE, 3000);
        newItemWithPrice(DRUG_ITEM_CODE, 2000);
        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", DRUG_ITEM_CODE)
                .put("genericName", "IT 退费回滚药品")
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
                        + " VALUES (?, ?, 'OUTP_PHARM', 'B-ITR-01', ?, ?, 50, 0, 'IN_STOCK')",
                BATCH_ID,
                drugId,
                java.sql.Date.valueOf("2025-06-01"),
                java.sql.Date.valueOf("2027-06-01"));
        jdbcTemplate.update(
                "INSERT INTO outpatient.schedule_template (id, dept_code, doctor_id, eff_from, eff_to,"
                        + " week_pattern, session, appt_type, slot_start, slot_end, slot_quota, room,"
                        + " release_days, release_time, status)"
                        + " VALUES (?, ?, '3', CURRENT_DATE, NULL, '1111111', 'MORNING', 'GENERAL',"
                        + " TIME '08:00', TIME '12:00', 5, 'IT-ROOM-R1', 1, TIME '07:00', 'ACTIVE')",
                TEMPLATE_ID,
                DEPT_CODE);
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("endDate", LocalDate.now().plusDays(1).toString()).put("days", 2);
        assertThat(postJson("/api/v1/outpatient/schedules/generate", adminToken, generate)
                        .asInt())
                .as("两日窗口×单模板应生成两行排班")
                .isEqualTo(2);
        todayPoolId = jdbcTemplate.queryForObject(
                "SELECT p.id FROM outpatient.appt_number_pool p"
                        + " JOIN outpatient.schedule s ON s.id = p.schedule_id"
                        + " WHERE s.sched_date = ? AND p.deleted = 0 AND s.deleted = 0 ORDER BY p.id LIMIT 1",
                Long.class,
                LocalDate.now());
        tomorrowPoolId = jdbcTemplate.queryForObject(
                "SELECT p.id FROM outpatient.appt_number_pool p"
                        + " JOIN outpatient.schedule s ON s.id = p.schedule_id"
                        + " WHERE s.sched_date = ? AND p.deleted = 0 AND s.deleted = 0 ORDER BY p.id LIMIT 1",
                Long.class,
                LocalDate.now().plusDays(1));
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PAID_PATIENT_ID,
                "退费回滚患者甲");

        // 窗口挂号一步 TAKEN（同事务签发 visit）
        ObjectNode book = objectMapper.createObjectNode();
        book.put("patientId", PAID_PATIENT_ID).put("poolId", todayPoolId).put("channel", "WINDOW");
        JsonNode booked = postJson("/api/v1/outpatient/appointments", adminToken, book);
        apptNo = booked.path("apptNo").asText();
        visitId = booked.path("visitId").asText();
        assertThat(booked.path("status").asText()).isEqualTo("TAKEN");
        assertThat(visitId).matches("O\\d{13}");

        // 挂号费手工计费通道（裁决 7：POST /fees/manual reason=门诊挂号费）+ 单独结算 → 回执回填 PAID
        ObjectNode manual = objectMapper.createObjectNode();
        manual.put("patientId", PAID_PATIENT_ID)
                .put("visitId", visitId)
                .put("itemCode", REG_ITEM_CODE)
                .put("quantity", "1")
                .put("reason", "门诊挂号费");
        assertThat(postForEntity("/api/v1/billing/fees/manual", adminToken, manual)
                        .getStatusCode()
                        .is2xxSuccessful())
                .as("手工计费落费应 2xx（实测 201 Created）")
                .isTrue();
        settleVisit(visitId, 1000);
        for (int i = 0; i < 100; i++) {
            Map<String, Object> appt = jdbcTemplate.queryForMap(
                    "SELECT fee_status, fee_settlement_id FROM outpatient.appointment WHERE appt_no = ?", apptNo);
            if ("PAID".equals(appt.get("fee_status")) && appt.get("fee_settlement_id") != null) {
                break;
            }
            Thread.sleep(100);
        }
        Map<String, Object> apptRow = jdbcTemplate.queryForMap(
                "SELECT fee_status, fee_settlement_id FROM outpatient.appointment WHERE appt_no = ?", apptNo);
        assertThat(apptRow.get("fee_status")).as("挂号费收费回填应置 PAID").isEqualTo("PAID");
        assertThat(apptRow.get("fee_settlement_id")).as("挂号费结算锚应回填").isNotNull();

        // 分诊报到→叫号→接诊（visit IN_CONSULT，后续费用链待缴费推进的前提态）
        ObjectNode checkIn = objectMapper.createObjectNode();
        checkIn.put("visitId", visitId).put("stationId", "IT-STATION-R1");
        assertThat(postJson("/api/v1/outpatient/triage/check-in", adminToken, checkIn)
                        .path("status")
                        .asText())
                .isEqualTo("WAITING");
        ObjectNode call = objectMapper.createObjectNode();
        call.put("deptCode", DEPT_CODE).put("doctorId", "3");
        assertThat(postJson("/api/v1/outpatient/queue/call", adminToken, call)
                        .path("status")
                        .asText())
                .isEqualTo("CALLED");
        assertThat(postEmpty("/api/v1/outpatient/visits/" + visitId + "/admit", adminToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 检查单 A（LAB-001×1）→ 真实 order.created → PENDING 费用（ORDER_CONFIRMED/sourceRef=orderNo）
        ObjectNode examOrder = objectMapper.createObjectNode();
        examOrder.put("orderType", "LAB");
        examOrder.putArray("items").addObject().put("itemCode", LAB_ITEM_CODE).put("quantity", "1");
        orderNoA = postJson("/api/v1/outpatient/visits/" + visitId + "/orders", doctorToken, examOrder)
                .path("orderNo")
                .asText();
        assertThat(orderNoA).matches("OP\\d{14}");
        examFeeId = awaitPendingFee(orderNoA, "ORDER_CONFIRMED", 3000);
        examSettlementId = settleAndLocateSettlement(orderNoA, 3000);
        awaitOrderStatus(orderNoA, "CHARGED");

        // 处方 B（V704 演示医师开方）→ 药品费用 PENDING（PRESCRIPTION_EFFECTIVE/sourceRef=rxNo）
        ObjectNode rxReq = objectMapper.createObjectNode();
        rxReq.put("rxType", "OUTPATIENT");
        ObjectNode line = rxReq.putArray("items").addObject();
        line.put("drugId", drugId)
                .put("quantity", "2")
                .put("routeCode", "ORAL")
                .put("frequency", "TID")
                .put("days", 3)
                .put("singleDose", "0.5g");
        rxNo = postJson("/api/v1/outpatient/visits/" + visitId + "/prescriptions", doctorToken, rxReq)
                .path("extRef")
                .asText();
        assertThat(rxNo).as("开方衔接出参 extRef 应承载 rxNo").matches("R\\d{14}");
        drugFeeId = awaitPendingFee(rxNo, "PRESCRIPTION_EFFECTIVE", 4000);
        drugSettlementId = settleAndLocateSettlement(rxNo, 4000);

        // 放行终态：RX_REF 引用行 CHARGED（检查单 A 已于其结算段后就近等待 CHARGED）
        // + 处方 PENDING_DISPENSE（真实 order.charged 精确放行）
        awaitOrderStatus(rxRefOrderNo(rxNo), "CHARGED");
        awaitRxDispenseReleased(rxNo);
        assertThat(rxStatus(rxNo)).as("药品结算放行后处方应 PENDING_DISPENSE").isEqualTo("PENDING_DISPENSE");
        JsonNode dispense = awaitDispense(rxNo);
        dispenseNo = dispense.path("dispenseNo").asText();
        prescriptionItemId =
                dispense.path("items").get(0).path("prescriptionItemId").asText();
    }

    /** 单笔结算（仅一笔在缴费用）并返回该笔费用行的结算单 id。 */
    private long settleAndLocateSettlement(String sourceRef, long amountFen) throws InterruptedException {
        String settleNo = settleVisit(visitId, amountFen);
        // 等该结算单下本来源单据费用行 SETTLED 后读回结算锚（结算回执异步回挂，禁盲等）
        for (int i = 0; i < 100; i++) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT settlement_id FROM billing.fee_record"
                            + " WHERE source_ref = ? AND trigger_point IN ('ORDER_CONFIRMED','PRESCRIPTION_EFFECTIVE')"
                            + " AND status = 'SETTLED' AND settlement_id IS NOT NULL",
                    Long.class,
                    sourceRef);
            if (!ids.isEmpty()) {
                String actualNo = jdbcTemplate.queryForObject(
                        "SELECT settle_no FROM billing.settlement WHERE id = ?", String.class, ids.get(0));
                if (settleNo.equals(actualNo)) {
                    return ids.get(0);
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("结算锚定位超时：sourceRef=" + sourceRef + "，settleNo=" + settleNo);
    }

    /** 读 RX_REF 引用行业务号（ext_ref=rxNo 反查 order_no，作废/断言锚）。 */
    private String rxRefOrderNo(String rxNoText) {
        return jdbcTemplate.queryForObject(
                "SELECT order_no FROM outpatient.clinic_order WHERE ext_ref = ? AND order_type = 'RX_REF'",
                String.class,
                rxNoText);
    }

    /** 轮询等待处方 PENDING_DISPENSE 且发药单 CREATED（charged 放行异步建单，上限 10s）。 */
    private void awaitRxDispenseReleased(String rxNoText) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer released = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pharmacy.prescription p"
                            + " JOIN pharmacy.dispense d ON d.rx_no = p.rx_no AND d.deleted = 0"
                            + " WHERE p.rx_no = ? AND p.status = 'PENDING_DISPENSE' AND d.status = 'CREATED'",
                    Integer.class,
                    rxNoText);
            if (released != null && released == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("charged 放行超时：rxNo=" + rxNoText);
    }

    /** 轮询等待发药单入队并回出参（放行异步建单，上限 10s；返回首行 VO）。 */
    private JsonNode awaitDispense(String rxNoText) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode array = getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNoText, adminToken);
            if (array.isArray() && array.size() == 1) {
                return array.get(0);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("发药单入队超时：rxNo=" + rxNoText);
    }

    @Test
    @Order(2)
    @DisplayName(
            "退药→免审退费→回滚：批次回补+RX_REF 镜像 FULL_RETURNED+药品结算零 order.cancelled 且检查单 A 保持 CHARGED；检查单退费扇出 order.cancelled 精确清单")
    void dispensedReturnTriggersRefundAndRollback() throws Exception {
        // 发药签名（配药 admin+核对 reviewer 双签）→ 处方 DISPENSED+占用 DISPENSED+批次 48/0
        ObjectNode pick = objectMapper.createObjectNode();
        ObjectNode pickLine = pick.putArray("items").addObject();
        pickLine.put("prescriptionItemId", prescriptionItemId);
        pickLine.putArray("traceCodes").add("TR-ITR-001").add("TR-ITR-002");
        assertThat(postForEntity("/api/v1/pharmacy/dispenses/" + dispenseNo + "/pick", adminToken, pick)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNo + "/verify", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNo + "/issue", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(rxStatus(rxNo)).isEqualTo("DISPENSED");
        awaitOccupancy(rxNo, "DISPENSED");
        assertThat(batchField("quantity")).isEqualTo(48L);
        assertThat(batchField("locked_qty")).isEqualTo(0L);

        // 退药受理（追溯码逐码核验防回流）→ 发药单 FULL_RETURNED+批次回补 50+正反冲流水勾稽
        ObjectNode ret = objectMapper.createObjectNode();
        ret.put("dispenseNo", dispenseNo).put("mode", "ISSUED_RETURN");
        ObjectNode retLine = ret.putArray("items").addObject();
        retLine.put("prescriptionItemId", prescriptionItemId).put("returnQuantity", "2");
        retLine.putArray("traceCodes").add("TR-ITR-001").add("TR-ITR-002");
        assertThat(postForEntity("/api/v1/pharmacy/dispense-returns", adminToken, ret)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNo, adminToken)
                        .get(0)
                        .path("status")
                        .asText())
                .isEqualTo("FULL_RETURNED");
        assertThat(batchField("quantity")).as("退药批次回补勾稽").isEqualTo(50L);
        Integer restockRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE ref_doc = ? AND action = 'RETURN_RESTOCK'"
                        + " AND quantity = 2",
                Integer.class,
                dispenseNo);
        assertThat(restockRows).as("回补流水恰一行 +2").isEqualTo(1);
        // 退药受理回流（pharmacy.dispense.returned 双通道之一）：RX_REF 镜像 FULL_RETURNED
        for (int i = 0; i < 100; i++) {
            String mirror = jdbcTemplate.queryForObject(
                    "SELECT dispense_status FROM outpatient.clinic_order WHERE ext_ref = ?"
                            + " AND order_type = 'RX_REF'",
                    String.class,
                    rxNo);
            if ("FULL_RETURNED".equals(mirror)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT dispense_status FROM outpatient.clinic_order WHERE ext_ref = ?"
                                + " AND order_type = 'RX_REF'",
                        String.class,
                        rxNo))
                .as("RX_REF 发药回流镜像应 FULL_RETURNED")
                .isEqualTo("FULL_RETURNED");
        // BILL-1017 硬前置解锁：占用回 NONE（returned→billing 占用回写异步收敛）——退费 apply 前置等待
        awaitOccupancy(rxNo, "NONE");

        // 免审退费 apply（药品结算 4000≤阈值、当日 DAY_CORRECTION、零占用）→ 落库即 APPROVED
        long refundCountBefore = refundApprovedFrameCount();
        ObjectNode apply = objectMapper.createObjectNode();
        apply.put("settlementId", drugSettlementId).put("reason", "IT 退药后当日免审直退");
        apply.putArray("lines").addObject().put("feeId", drugFeeId).put("refundQuantity", "2");
        long drugRefundId =
                postJson("/api/v1/billing/refunds", adminToken, apply).asLong();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT auto_approved::text FROM billing.refund_request WHERE id = ?",
                        String.class,
                        drugRefundId))
                .as("当日免审直退应 auto_approved=true")
                .isEqualTo("true");
        // refund.approved 立达（免审 apply 同事件承载）：autoApproved=true 与结算锚勾稽
        assertThat(ItCaptureConfig.REFUND_LATCH.await(10, TimeUnit.SECONDS))
                .as("billing.refund.approved 事件应可消费")
                .isTrue();
        assertThat(ItCaptureConfig.CAPTURED.stream()
                        .filter(e -> BillingMessagingConstants.EVENT_REFUND_APPROVED.equals(e.eventType()))
                        .filter(e -> String.valueOf(drugSettlementId)
                                .equals(e.payload().path("settlementId").asText()))
                        .findFirst()
                        .orElseThrow()
                        .payload()
                        .path("autoApproved")
                        .asBoolean())
                .isTrue();

        // 已发药终态收敛（refund.approved ByRx 通道——Task 11 双通道之二）：处方 FULL_RETURNED
        awaitRxStatus(rxNo, "FULL_RETURNED");

        // 误伤面闭合（brief 加粗核心断言，裁决 5/6 结算精确语义）：药品结算 orderRefs 空 → 零
        // order.cancelled 扇出 → 同 visit 检查单 A 保持 CHARGED、费用行不受牵连
        assertThat(orderStatus(orderNoA))
                .as("药品退费后同 visit 检查单 A 应保持 CHARGED（误伤面闭合）")
                .isEqualTo("CHARGED");
        assertThat(orderCancelledFrameCount())
                .as("药品-only 结算退费不应扇出 order.cancelled（orderRefs 空）")
                .isEqualTo(refundCountBefore);

        // order.cancelled 正向覆盖（同机制 e2e）：检查单 A 自身退费结算 → 逐单扇出携 rxNos 精确
        // 空清单（结算无药品处方），A CHARGED→CANCELLED 逆向
        ObjectNode applyExam = objectMapper.createObjectNode();
        applyExam.put("settlementId", examSettlementId).put("reason", "IT 检查单当日免审直退");
        applyExam.putArray("lines").addObject().put("feeId", examFeeId).put("refundQuantity", "1");
        postJson("/api/v1/billing/refunds", adminToken, applyExam);
        assertThat(ItCaptureConfig.ORDER_CANCELLED_LATCH.await(10, TimeUnit.SECONDS))
                .as("outpatient.order.cancelled 事件应可消费")
                .isTrue();
        EventEnvelope cancelled = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> OutpatientMessagingConstants.EVENT_ORDER_CANCELLED.equals(e.eventType()))
                .filter(e -> orderNoA.equals(e.payload().path("orderNo").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelled.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(cancelled.payload().path("rxNos").isArray()).isTrue();
        assertThat(cancelled.payload().path("rxNos").size())
                .as("order.cancelled rxNos 应为结算反查精确清单（检查单结算无药品=空）")
                .isZero();
        assertThat(cancelled.payload().path("reason").asText()).isEqualTo("退费逆向终态确认");
        awaitOrderStatus(orderNoA, "CANCELLED");
    }

    /** 已捕获的 refund.approved 帧数（「无 refund 帧」断言的差值基线锚）。 */
    private long refundApprovedFrameCount() {
        return ItCaptureConfig.CAPTURED.stream()
                .filter(e -> BillingMessagingConstants.EVENT_REFUND_APPROVED.equals(e.eventType()))
                .count();
    }

    /** 已捕获的 order.cancelled 帧数（零扇出负断言锚）。 */
    private long orderCancelledFrameCount() {
        return ItCaptureConfig.CAPTURED.stream()
                .filter(e -> OutpatientMessagingConstants.EVENT_ORDER_CANCELLED.equals(e.eventType()))
                .count();
    }

    @Test
    @Order(3)
    @DisplayName("未支付 portal 预约退号：免退费直取消→池回补 used_count=0+无新增 refund 帧+appointment.cancelled feeRefundTriggered=false")
    void unpaidReservationCancelReleasesWithoutBilling() throws Exception {
        // portal 匿名建档（介质解析前置：ID_CARD 标识 ACTIVE 在档）
        ObjectNode archive = objectMapper.createObjectNode();
        archive.put("name", "portal 预约患者")
                .put("sex", "1")
                .put("birthDate", "1992-03-21")
                .put("idCardNo", PORTAL_ID_CARD)
                .put("mobile", "13900009101")
                .put("address", "北京市海淀区合成验收路2号")
                .put("registerChannel", "WINDOW")
                .put("archiveSource", "STANDARD")
                .put("informedConsentRef", "IT-CONSENT-PORTAL");
        JsonNode created = postJson("/api/v1/patient/patients", adminToken, archive);
        long portalPatientId = created.path("candidatePatientId").asLong();
        assertThat(created.path("outcome").asText()).isEqualTo("NO_MATCH");
        assertThat(portalPatientId).isPositive();

        // portal 免登录预约（明日池——今日池线上退号时限外 OP-1010）：RESERVED 占位+pay_deadline
        ObjectNode book = objectMapper.createObjectNode();
        book.put("credentialType", "ID_CARD")
                .put("credentialNo", PORTAL_ID_CARD)
                .put("poolId", tomorrowPoolId);
        JsonNode booked = postJson("/api/v1/outpatient/portal/appointments", (String) null, book);
        String portalApptNo = booked.path("apptNo").asText();
        assertThat(booked.path("status").asText()).as("portal 预约占位 RESERVED").isEqualTo("RESERVED");
        assertThat(booked.path("payDeadline").isNull()).as("占位单应携支付时限").isFalse();
        assertThat(poolUsedCount(tomorrowPoolId)).as("预约占用一号").isEqualTo(1);

        // 匿名退号（分支 1：UNPAID 无结算锚→免退费直取消+回池）
        long refundFramesBefore = refundApprovedFrameCount();
        ObjectNode cancel = objectMapper.createObjectNode();
        cancel.put("reason", "IT 未支付退号");
        JsonNode cancelled =
                postJson("/api/v1/outpatient/portal/appointments/" + portalApptNo + "/cancel", (String) null, cancel);
        assertThat(cancelled.path("status").asText()).as("免退费直取消应 CANCELLED").isEqualTo("CANCELLED");

        // 池回补+零退费帧+appointment.cancelled 载荷勾稽
        assertThat(poolUsedCount(tomorrowPoolId)).as("退号回池 used_count 应归零").isZero();
        assertThat(refundApprovedFrameCount()).as("未支付退号不应产生任何 refund 帧").isEqualTo(refundFramesBefore);
        assertThat(ItCaptureConfig.APPT_CANCELLED_LATCH.await(10, TimeUnit.SECONDS))
                .as("outpatient.appointment.cancelled 事件应可消费")
                .isTrue();
        EventEnvelope frame = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> OutpatientMessagingConstants.EVENT_APPOINTMENT_CANCELLED.equals(e.eventType()))
                .filter(e -> portalApptNo.equals(e.payload().path("apptNo").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(frame.payload().path("feeRefundTriggered").asBoolean())
                .as("免退费路径 feeRefundTriggered 应为 false")
                .isFalse();
        assertThat(frame.payload().path("patientId").asLong()).isEqualTo(portalPatientId);
    }

    @Test
    @Order(4)
    @DisplayName("支付超时释放：PORTAL 占位超 2s 延迟档位→NO_SHOW+池回补（DB+Redis 池键归总量）+爽约信用行限约区间写入")
    void timeoutNoShowReleasesPoolAndRecordsCredit() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                TIMEOUT_PATIENT_ID,
                "超时释放患者乙");

        // PORTAL 渠道占位（延迟信封入 appointment-timeout 档位——「延迟信封已入队」留痕为信封
        // 投递面的真栈实证；broker 惰性到期缺口见下注记）
        ObjectNode book = objectMapper.createObjectNode();
        book.put("patientId", TIMEOUT_PATIENT_ID).put("poolId", todayPoolId).put("channel", "PORTAL");
        // 基线差值断言（主链造数已占用一号，本用例只对增量负责）
        long usedBefore = poolUsedCount(todayPoolId);
        String redisBefore = redisTemplate.opsForValue().get(poolKey(todayPoolId));
        JsonNode booked = postJson("/api/v1/outpatient/appointments", adminToken, book);
        assertThat(booked.path("status").asText()).isEqualTo("RESERVED");
        assertThat(poolUsedCount(todayPoolId)).as("占位应较基线多扣一号").isEqualTo(usedBefore + 1);

        // 超时释放链收敛：RESERVED→NO_SHOW。quorum 延迟队列到期帧无消费者时不死信投递（45s
        // 纯等待与 basic.get 轮询两形态实证零投递——惰性到期语义，fy.delay 档位投递缺口随 PR
        // 级 concern 申报）；业务链断言按 stub 注入红线以合成信封走生产死信同一路由键
        // （DLX 目标=fy.topic + outpatient.appointment.timeout → 本模块超时监听器），先纯等
        // TTL 窗口 3s 再注入（brief「预约后等 3s」时点保留），禁盲等
        String apptNoText = booked.path("apptNo").asText();
        Thread.sleep(3000);
        ObjectNode timeoutPayload = objectMapper.createObjectNode();
        timeoutPayload
                .put("apptNo", apptNoText)
                .put("patientId", TIMEOUT_PATIENT_ID)
                .put("poolId", todayPoolId);
        rabbitTemplate.convertAndSend(
                "fy.topic",
                OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT,
                envelopeCodec.create(
                        java.time.Clock.systemUTC(),
                        OutpatientMessagingConstants.MODULE,
                        OutpatientMessagingConstants.EVENT_APPOINTMENT_TIMEOUT,
                        "it-timeout-" + apptNoText,
                        objectMapper.convertValue(timeoutPayload, Map.class)));
        for (int i = 0; i < 100; i++) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM outpatient.appointment WHERE appt_no = ?", String.class, apptNoText);
            if ("NO_SHOW".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM outpatient.appointment WHERE appt_no = ?", String.class, apptNoText))
                .as("支付超时应置 NO_SHOW（延迟档位回调释放）")
                .isEqualTo("NO_SHOW");

        // 双面回池勾稽：DB 权威库存与 Redis 快路径均回补至占位前基线（主链造数占用不受牵连）
        assertThat(poolUsedCount(todayPoolId)).as("超时释放 DB 回池至基线").isEqualTo(usedBefore);
        assertThat(redisTemplate.opsForValue().get(poolKey(todayPoolId)))
                .as("超时释放 Redis 池键回补至基线")
                .isEqualTo(redisBefore);

        // 爽约信用行：NO_SHOW 台账落行且限约区间写入（测试域阈值=1，首笔即达限约）
        Map<String, Object> credit = jdbcTemplate.queryForMap(
                "SELECT restrict_from, restrict_to FROM outpatient.appt_credit_record"
                        + " WHERE patient_id = ? AND action = 'NO_SHOW' ORDER BY id DESC LIMIT 1",
                TIMEOUT_PATIENT_ID);
        assertThat(credit.get("restrict_from")).as("爽约信用限约起始日应写入").isNotNull();
        assertThat(credit.get("restrict_to")).as("爽约信用限约截止日应写入").isNotNull();
    }
}

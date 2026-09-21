package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
 * PR-4 验收锚点①：处方→发药→退药全链真栈（HTTP/MQ/DB 零 mock）——
 * 药品+批次造数→开方（断言 billing PENDING 费用 TriggerType=PRESCRIPTION_EFFECTIVE/sourceRef=rxNo）
 * →注入 charged 帧→pick/verify/issue→断言 billing 占用 DISPENSED→退药受理→免审退费
 * apply/execute→refund.approved→处方 FULL_RETURNED+占用回退+批次回补勾稽。
 * 双签第二人经 seedReviewerUser（it-reviewer）承载核对位。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PharmacyPrescriptionFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（第 2 轮审查 P1-1 裁决：容器禁收敛入基类——共享 broker 会令跨 IT 手工
     *  捕获队列互相抢消息串扰；每 IT 独占一套、tag 与 compose 严格一致，BillingSettlementFlowIT
     *  :62-78 逐字同型；@DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载，本类不重复声明） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（会话/缓存/幂等前置键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（quorum 默认类型服务端配置与 compose 同语义；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 运行唯一院内码（防重跑撞 uk_drug_code；drug.itemCode 与 billing 收费项目共码——计费行映射依据） */
    private static final String DRUG_CODE = "D-IT-" + (System.nanoTime() % 1_000_000L);

    /** 收费项目码=药品共码（同值，开方计费行 itemCode 与发药明细 itemCode 同源勾稽） */
    private static final String ITEM_CODE = DRUG_CODE;

    /** CF-3 结构合法门诊就诊号（主链全五段用 / 第二处方作废用） */
    private static final String VISIT = visitId("00001");

    private static final String VISIT_2 = visitId("00002");

    /** 门诊患者主索引（处方/费用/退费链共用的患者维度锚点） */
    private static final long PATIENT_ID = 700201L;

    /** 批次账造数行主键（V703 零数据行——IT 经 jdbcTemplate 直插造数，偏差⑧红线） */
    private static final long BATCH_ID = 910201L;

    /** V303 种子超管 id（picker 留痕口径=登录会话 userId 十进制文本，AuthTokenInterceptor:76） */
    private static final long ADMIN_USER_ID = 1L;

    private static String adminToken = "";
    private static String reviewerToken = "";

    /** V704 演示医师登录名（sys_user id=3，PRESCRIPTION 执业授权种子——开方操作者须持权医师） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    private static String doctorToken = "";

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static long drugId;

    private static String rxNo = "";
    private static String dispenseNo = "";
    private static String prescriptionItemId = "";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /** 生成结构合法的 CF-3 就诊号（O + yyyyMMdd + 5 位流水段，BillingSettlementFlowIT :107-110 同型）。 */
    private static String visitId(String serial5) {
        return "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + serial5;
    }

    /**
     * 捕获队列声明（A.5-4 治理红线：禁止测试自声明交换机/裸队列——经 MessagingGovernance 声明，
     * BillingSettlementFlowIT ItCaptureConfig :121-148 同型）。一事件一队列：
     * q.it.pharmacy.dispense.completed / q.it.pharmacy.dispense.returned（q.&lt;消费模块&gt;.&lt;事件三段名&gt;，
     * 与构件命名规则同源，禁手写字面量）。声明副作用 registerSubscriber 会把 "it" 追加进 V702 两
     * 事件的 subscriber_modules——「it」作消费方模块标识仅进订阅清单不改登记行，与 billing 先例同款。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED;

        static final String Q_RETURNED =
                MessagingConstants.QUEUE_PREFIX + "it." + PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED;

        static final CountDownLatch COMPLETED_LATCH = new CountDownLatch(1);

        static final CountDownLatch RETURNED_LATCH = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itDispenseCompletedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED));
        }

        @Bean
        Declarables itDispenseReturnedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED));
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
        @RabbitListener(queues = {ItCaptureConfig.Q_COMPLETED, ItCaptureConfig.Q_RETURNED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            if (PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.COMPLETED_LATCH.countDown();
            } else if (PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED.equals(envelope.eventType())) {
                ItCaptureConfig.RETURNED_LATCH.countDown();
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
        // 无体响应（void/204 端点）回 MISSING 单例，防 readTree 拒 null 中断用例（FlowIT :182-194 同型收口）
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return toNode(restTemplate
                .postForEntity(path, new HttpEntity<>(body, headers), String.class)
                .getBody());
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体并发/守卫断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 无体 POST 助手（pick/verify/issue 等 void 端点的状态码断言面）。 */
    private ResponseEntity<String> postEmpty(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
    }

    /**
     * 注入门诊缴费放行事件帧（PR-5 起 outpatient.order.charged 有真实发布点——IT 内仍一律
     * RabbitTemplate + EventEnvelopeCodec 手工注入合成信封，BillingSettlementFlowIT :240-284 形态；
     * 载荷携 rxNos 精确清单——消费侧按单据放行，裁决 4；每次调用信封内 eventId 均为新 UUID，
     * 重投语义由调用方以新 traceId 表达）。
     *
     * @param visitId 放行目标就诊号
     * @param rxNos   本次结算覆盖的处方号精确清单（PharmacyChargedOrderListener 载荷守卫+逐单放行）
     * @param traceId 全链路追踪号（同时作日志检索锚点，重投帧以 -replay 后缀区分）
     */
    private void publishCharged(String visitId, List<String> rxNos, String traceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", "IT-RX-ORDER-" + visitId)
                .put("patientId", PATIENT_ID)
                .put("visitId", visitId);
        ArrayNode rxNosNode = payload.putArray("rxNos");
        for (String rxNo : rxNos) {
            rxNosNode.add(rxNo);
        }
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "outpatient.order.charged",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "outpatient",
                        "outpatient.order.charged",
                        traceId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    /**
     * 轮询等待该处方号的 PRESCRIPTION_EFFECTIVE 费用行到位（事件驱动异步，上限 10s，FlowIT
     * awaitFees :288-297 形态；列集含 billing_key——pharmacy 放行链消费契约的三段式依据）。
     *
     * @param rxNo 处方号（=fee_record.source_ref）
     * @return 费用行集（非空；恰 1 行断言由调用方落）
     */
    private List<Map<String, Object>> awaitFeeRows(String rxNo) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, amount, trigger_point, source_ref, billing_key FROM billing.fee_record"
                            + " WHERE source_ref = ? AND trigger_point = 'PRESCRIPTION_EFFECTIVE'",
                    rxNo);
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：rxNo=" + rxNo);
    }

    /** 读处方当前状态（SQL 直读，轮询与终态断言共用）。 */
    private String rxStatus(String rxNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.prescription WHERE rx_no = ?", String.class, rxNo);
    }

    /** 轮询等待处方到达目标状态（跨服务异步链收敛等待，上限 10s，禁盲等）。 */
    private void awaitRxStatus(String rxNo, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(rxStatus(rxNo))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("处方状态迁移超时：rxNo=" + rxNo + "，期望 " + expected);
    }

    /** 读该处方号费用行的执行占用状态（billing 占用回写异步收敛的断言锚点）。 */
    private String occupancy(String rxNo) {
        return jdbcTemplate.queryForObject(
                "SELECT exec_occupy_status FROM billing.fee_record"
                        + " WHERE source_ref = ? AND trigger_point = 'PRESCRIPTION_EFFECTIVE'",
                String.class,
                rxNo);
    }

    /** 轮询等待占用状态收敛到目标值（发药 DISPENSED / 全退 NONE，上限 10s，禁盲等）。 */
    private void awaitOccupancy(String rxNo, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(occupancy(rxNo))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("占用回写超时：rxNo=" + rxNo + "，期望 " + expected);
    }

    /** 轮询等待该处方号费用行到达目标状态（SETTLED / FULL_REFUND，上限 10s，禁盲等）。 */
    private void awaitFeeStatus(String rxNo, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM billing.fee_record"
                            + " WHERE source_ref = ? AND trigger_point = 'PRESCRIPTION_EFFECTIVE'",
                    String.class,
                    rxNo);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用状态迁移超时：rxNo=" + rxNo + "，期望 " + expected);
    }

    /** 轮询等待发药单入队并回出参（charged 放行异步建单，上限 10s；返回首行 VO）。 */
    private JsonNode awaitDispense(String rxNo) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode array = getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNo, adminToken);
            if (array.isArray() && array.size() == 1) {
                return array.get(0);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("发药单入队超时：rxNo=" + rxNo);
    }

    /** 该处方号发药单行数（幂等断言锚点：uk_dispense_rx_active 恰一活动单）。 */
    private long countDispense(String rxNo) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.dispense WHERE rx_no = ? AND deleted = 0", Long.class, rxNo);
        return n == null ? 0 : n;
    }

    /** 读批次账数值列（列名仅取本类固定字面量 quantity/locked_qty，禁外部拼接）。 */
    private long batchField(long batchId, String column) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM pharmacy.drug_batch WHERE id = ?", Long.class, batchId);
        return v == null ? -1 : v;
    }

    @Test
    @Order(1)
    @DisplayName("前置：双账号登录；建 billing 项目+发布价；建药品（对照 itemCode）并 SQL 造批次（50 盒）")
    void prepareDrugBatchAndPrice() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        // doctordemo 承载开方位——Task 9 practice/check 接线后开方须持 PRESCRIPTION 授权的医师
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);

        // billing 侧：建收费项目（与药品 itemCode 共码）+ 定价 3000 分 + 发布生效（FlowIT newItemWithPrice 同型）
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", ITEM_CODE)
                .put("itemName", "IT 验收药品项目 " + ITEM_CODE)
                .put("itemClass", "TREATMENT")
                .put("unit", "盒")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        long itemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（BillingSettlementFlowIT :332 注记同源）
        draft.put("itemCode", ITEM_CODE)
                .put("price", 3000)
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

        // pharmacy 侧：药品建档（nhsa 留空=未对照；未对照可院内启用但显式标记不可医保结算）
        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", DRUG_CODE)
                .put("genericName", "IT 验收药品 " + DRUG_CODE)
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
                .put("itemCode", ITEM_CODE);
        JsonNode drugVo = postJson("/api/v1/pharmacy/drugs", adminToken, drug);
        drugId = drugVo.path("id").asLong();
        // 未对照显式标记断言点：insuredSettleable=false（派生自 nhsaCode 为空的契约）
        assertThat(drugVo.path("insuredSettleable").asBoolean()).isFalse();
        assertThat(drugVo.path("itemCode").asText()).isEqualTo(ITEM_CODE);

        // 医保编码对照后派生标记翻转 true（对照走独立端点留痕变更类型 MAPPING）
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("nhsaCode", "XJ-IT-0001").put("catalogVersion", "2026-A").put("payType", "YI");
        postJson("/api/v1/pharmacy/drugs/" + drugId + "/insurance-mapping", adminToken, mapping);
        assertThat(getJson("/api/v1/pharmacy/drugs/" + drugId, adminToken)
                        .path("insuredSettleable")
                        .asBoolean())
                .isTrue();

        // 批次 SQL 直插（V703 零数据行——批次属运行时数据，IT 造数红线偏差⑧；50 盒口径）
        jdbcTemplate.update(
                "INSERT INTO pharmacy.drug_batch (id, drug_id, storehouse, batch_no, production_date,"
                        + " expire_date, quantity, locked_qty, status)"
                        + " VALUES (?, ?, 'OUTP_PHARM', 'B-IT-01', ?, ?, 50, 0, 'IN_STOCK')",
                BATCH_ID,
                drugId,
                java.sql.Date.valueOf("2025-06-01"),
                java.sql.Date.valueOf("2027-06-01"));
        // 数据库写操作核验：造数行在账且可用量=50、零锁定
        assertThat(batchField(BATCH_ID, "quantity")).isEqualTo(50L);
        assertThat(batchField(BATCH_ID, "locked_qty")).isEqualTo(0L);
        String batchStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.drug_batch WHERE id = ?", String.class, BATCH_ID);
        assertThat(batchStatus).isEqualTo("IN_STOCK");
    }

    @Test
    @Order(2)
    @DisplayName("开方→billing PENDING 费用：PRESCRIPTION_EFFECTIVE/sourceRef=rxNo 快照实证")
    void createPrescriptionGeneratesPendingFees() throws InterruptedException {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("patientId", PATIENT_ID).put("visitId", VISIT).put("rxType", "OUTPATIENT");
        ObjectNode line = req.putArray("items").addObject();
        line.put("drugId", drugId)
                .put("quantity", "2")
                .put("routeCode", "ORAL")
                .put("frequency", "TID")
                .put("days", 3)
                .put("singleDose", "0.5g");
        JsonNode vo = postJson("/api/v1/pharmacy/prescriptions", doctorToken, req);
        rxNo = vo.path("rxNo").asText();
        // 处方号契约：R+yyyyMMdd+6 位（14 位数字段）；预检占位恒通过级（P3 引擎前固定 PASS）
        assertThat(rxNo).matches("R\\d{14}");
        assertThat(vo.path("reviewLevel").asText()).isEqualTo("PASS");
        assertThat(vo.path("items").get(0).path("itemCode").asText()).isEqualTo(ITEM_CODE);

        // 轮询（上限 10s）：billing PENDING 费用快照实证——恰好 1 行、金额服务端算 3000×2=6000 分
        List<Map<String, Object>> feeRows = awaitFeeRows(rxNo);
        assertThat(feeRows).hasSize(1);
        Map<String, Object> feeRow = feeRows.get(0);
        assertThat(feeRow.get("status")).isEqualTo("PENDING");
        assertThat(feeRow.get("trigger_point")).isEqualTo("PRESCRIPTION_EFFECTIVE");
        assertThat(feeRow.get("source_ref")).isEqualTo(rxNo);
        assertThat(((Number) feeRow.get("amount")).longValue()).isEqualTo(6000L);
        // billing_key 三段前缀勾稽（患者|来源单据|计费点——pharmacy 放行链按第二段回定位处方）
        assertThat(String.valueOf(feeRow.get("billing_key")))
                .startsWith(PATIENT_ID + "|" + rxNo + "|PRESCRIPTION_EFFECTIVE|");
    }

    @Test
    @Order(3)
    @DisplayName("缴费→charged 注入：处方 PENDING_DISPENSE+发药单 CREATED 入队（幂等重投不双建）")
    void chargedReleaseQueuesDispense() throws Exception {
        // R2-14 回执链先收敛（fee.created 驱动 APPROVED→PENDING_FEE）——charged 放行谓词依赖此前置，禁盲等
        awaitRxStatus(rxNo, "PENDING_FEE");

        // 预结算（6000=3000 分×2，与 Order(2) 断言的 fee_record amount 同源一致）+ 现金正式结算
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", VISIT).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong()).isEqualTo(6000L);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", pv.path("settleNo").asText());
        // 支付明细行：全自费现金一行（Σpayments.amount==totalAmount 勾稽硬约束）
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "6000");
        JsonNode sv = postJson("/api/v1/billing/settlements", adminToken, settle);
        assertThat(sv.path("status").asText()).isEqualTo("SETTLED");

        // 注入 charged 帧（stub 边界：生产零发布，IT 合成信封）→ 放行链异步收敛
        publishCharged(VISIT, List.of(rxNo), "it-rx-charged-" + VISIT);
        // 轮询：处方 PENDING_DISPENSE 且发药单 CREATED（双行迁移一次到位，上限 10s）
        for (int i = 0; i < 100; i++) {
            Integer released = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pharmacy.prescription p"
                            + " JOIN pharmacy.dispense d ON d.rx_no = p.rx_no AND d.deleted = 0"
                            + " WHERE p.rx_no = ? AND p.status = 'PENDING_DISPENSE' AND d.status = 'CREATED'",
                    Integer.class,
                    rxNo);
            if (released != null && released == 1) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(rxStatus(rxNo)).isEqualTo("PENDING_DISPENSE");
        assertThat(countDispense(rxNo)).isEqualTo(1);

        // 幂等重投：同 visitId 新 eventId 帧（业务级 CAS 重读定性 + uk_dispense_rx_active 兜底）
        publishCharged(VISIT, List.of(rxNo), "it-rx-charged-" + VISIT + "-replay");
        // 覆盖一次重投消费窗口（FlowIT Order(3) 同型有界等待，非盲等——入队态已先行实证）
        Thread.sleep(2000);
        assertThat(countDispense(rxNo)).as("charged 重复投递仅放行一次：发药单仍恰一张").isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("配药→核对→发药签名：批次锁定/追溯码逐码/双签（第二账号核对）/占用 DISPENSED")
    void pickVerifyIssueMarksOccupancy() throws Exception {
        // 发药单回检出号与处方明细定位（prescriptionItemId 作 pick 采集行锚点）
        JsonNode vo = awaitDispense(rxNo);
        dispenseNo = vo.path("dispenseNo").asText();
        prescriptionItemId = vo.path("items").get(0).path("prescriptionItemId").asText();

        // 配药（admin）：FEFO 选批锁定 + 追溯码逐盒采集（无码不结）
        ObjectNode pick = objectMapper.createObjectNode();
        ObjectNode pickLine = pick.putArray("items").addObject();
        pickLine.put("prescriptionItemId", prescriptionItemId);
        pickLine.putArray("traceCodes").add("TR-IT-001").add("TR-IT-002");
        assertThat(postForEntity("/api/v1/pharmacy/dispenses/" + dispenseNo + "/pick", adminToken, pick)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 扫码核对（reviewer）：与调配人互异——双签分权后端硬守卫（PH-1011）成立前提
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNo + "/verify", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 发药签名（reviewer）：批次扣减+出库流水同事务，处方转 DISPENSED
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNo + "/issue", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 单据面断言：ISSUED + 双签留痕（留痕口径=登录会话 userId 十进制文本）
        JsonNode issued =
                getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNo, adminToken).get(0);
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.path("picker").asText()).isEqualTo(String.valueOf(ADMIN_USER_ID));
        assertThat(issued.path("verifier").asText()).isEqualTo(String.valueOf(REVIEWER_USER_ID));
        // 处方终态基点迁移
        assertThat(rxStatus(rxNo)).isEqualTo("DISPENSED");
        // 批次勾稽：50 锁 2 发 2 → 48/0（锁定转扣减，零悬挂锁定）
        assertThat(batchField(BATCH_ID, "quantity")).isEqualTo(48L);
        assertThat(batchField(BATCH_ID, "locked_qty")).isEqualTo(0L);
        // 出库流水恰 1 行：ISSUE/-2/ref=dispenseNo（红线 2：批次变更必有对应流水）
        Integer issueRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE ref_doc = ? AND action = 'ISSUE' AND quantity = -2",
                Integer.class,
                dispenseNo);
        assertThat(issueRows).isEqualTo(1);
        // billing 占用回写（异步事件链）轮询收敛：NONE→DISPENSED
        awaitOccupancy(rxNo, "DISPENSED");

        // 捕获队列收到 completed 事件：批号+追溯码逐码载荷实证（V702 id 28 契约面）
        assertThat(ItCaptureConfig.COMPLETED_LATCH.await(10, TimeUnit.SECONDS))
                .as("pharmacy.dispense.completed 事件应可消费")
                .isTrue();
        EventEnvelope completed = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED.equals(e.eventType()))
                .filter(e -> dispenseNo.equals(e.payload().path("dispenseNo").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(completed.producer()).isEqualTo("pharmacy");
        assertThat(completed.payload().path("rxNo").asText()).isEqualTo(rxNo);
        JsonNode eventLine = completed.payload().path("lines").get(0);
        assertThat(eventLine.path("batchNo").asText()).isEqualTo("B-IT-01");
        List<String> eventTraces = new CopyOnWriteArrayList<>();
        eventLine.path("traceCodes").forEach(code -> eventTraces.add(code.asText()));
        assertThat(eventTraces).containsExactly("TR-IT-001", "TR-IT-002");
    }

    @Test
    @Order(5)
    @DisplayName("退药受理→免审退费→终态：批次回补勾稽+占用回 NONE+处方 FULL_RETURNED")
    void returnAndRefundConvergeTerminals() throws Exception {
        // 退药受理（ISSUED_RETURN 全量 2+同两码：追溯码逐码核验防回流）
        ObjectNode ret = objectMapper.createObjectNode();
        ret.put("dispenseNo", dispenseNo).put("mode", "ISSUED_RETURN");
        ObjectNode retLine = ret.putArray("items").addObject();
        retLine.put("prescriptionItemId", prescriptionItemId).put("returnQuantity", "2");
        retLine.putArray("traceCodes").add("TR-IT-001").add("TR-IT-002");
        assertThat(postForEntity("/api/v1/pharmacy/dispense-returns", adminToken, ret)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 单据面断言：发药单 FULL_RETURNED + 批次回补 50 + 流水恰两行正反冲勾稽
        assertThat(getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxNo, adminToken)
                        .get(0)
                        .path("status")
                        .asText())
                .isEqualTo("FULL_RETURNED");
        assertThat(batchField(BATCH_ID, "quantity")).isEqualTo(50L);
        assertThat(batchField(BATCH_ID, "locked_qty")).isEqualTo(0L);
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE ref_doc = ?", Integer.class, dispenseNo);
        assertThat(ledgerRows)
                .as("出库流水恰 2 行：ISSUE -2 与 RETURN_RESTOCK +2 正反冲勾稽")
                .isEqualTo(2);
        Integer restockRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE ref_doc = ? AND action = 'RETURN_RESTOCK' AND quantity = 2",
                Integer.class,
                dispenseNo);
        assertThat(restockRows).isEqualTo(1);

        // occupancy returnedQuantity 断言（code-review 修复验证面，brief 断言清单外增量）：退药受理
        //  同事务回写 prescription_item.returned_quantity 后，占用查询出「实发 2/已退 2」
        //  （修复前该列全 PR 无写入点、恒 null）。两数量列均数值比较：DECIMAL(12,3) 读回
        //  toPlainString 携库侧 3 位标度出 "2.000"，契约=D-18 DECIMAL string 直出，断言不绑文本标度
        JsonNode occupancyRows = getJson(
                "/api/v1/pharmacy/medication-occupancy?patientId=" + PATIENT_ID + "&visitId=" + VISIT + "&itemCode="
                        + ITEM_CODE,
                adminToken);
        assertThat(occupancyRows).hasSize(1);
        assertThat(new java.math.BigDecimal(
                        occupancyRows.get(0).path("issuedQuantity").asText()))
                .isEqualByComparingTo("2");
        assertThat(new java.math.BigDecimal(
                        occupancyRows.get(0).path("returnedQuantity").asText()))
                .isEqualByComparingTo("2");

        // returned 事件捕获：fullReturn=true（billing 占用回退与退费联动依据，V702 id 29 契约面）
        assertThat(ItCaptureConfig.RETURNED_LATCH.await(10, TimeUnit.SECONDS))
                .as("pharmacy.dispense.returned 事件应可消费")
                .isTrue();
        EventEnvelope returned = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> PharmacyMessagingConstants.EVENT_DISPENSE_RETURNED.equals(e.eventType()))
                .filter(e -> dispenseNo.equals(e.payload().path("dispenseNo").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(returned.payload().path("fullReturn").asBoolean()).isTrue();

        // BILL-1017 硬前置解锁：占用回 NONE（returned→billing 占用回写异步收敛）——退费 apply 前置等待，禁盲等
        awaitOccupancy(rxNo, "NONE");

        // billing 退费：当日免审直退（6000 ≤ 阈值、占用已回 NONE、当日 DAY_CORRECTION → 落库即 APPROVED）
        Long feeId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.fee_record WHERE source_ref = ? AND trigger_point = 'PRESCRIPTION_EFFECTIVE'",
                Long.class,
                rxNo);
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT settlement_id FROM billing.fee_record WHERE id = ?", Long.class, feeId);
        ObjectNode apply = objectMapper.createObjectNode();
        apply.put("settlementId", settlementId).put("reason", "IT 退药后当日免审直退");
        apply.putArray("lines").addObject().put("feeId", feeId).put("refundQuantity", "2");
        long refundId = postJson("/api/v1/billing/refunds", adminToken, apply).asLong();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT auto_approved::text FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("true");
        postJson("/api/v1/billing/refunds/" + refundId + "/execute", adminToken, objectMapper.createObjectNode());

        // 终态三面勾稽：refund.approved 链收敛处方 FULL_RETURNED；费用 FULL_REFUND；占用维持 NONE
        awaitRxStatus(rxNo, "FULL_RETURNED");
        awaitFeeStatus(rxNo, "FULL_REFUND");
        assertThat(occupancy(rxNo)).isEqualTo("NONE");
    }

    @Test
    @Order(6)
    @DisplayName("未缴费作废联动：第二处方 cancel 后 billing 费用 CANCELLED（端口同事务）")
    void cancelPendingFeeRevokesBillingFees() throws InterruptedException {
        // 第二就诊/同患者再开方（未缴费——不发 charged 帧，费用停留 PENDING）
        ObjectNode req = objectMapper.createObjectNode();
        req.put("patientId", PATIENT_ID).put("visitId", VISIT_2).put("rxType", "OUTPATIENT");
        ObjectNode line = req.putArray("items").addObject();
        line.put("drugId", drugId)
                .put("quantity", "1")
                .put("routeCode", "ORAL")
                .put("frequency", "TID")
                .put("days", 3)
                .put("singleDose", "0.5g");
        String rxNo2 = postJson("/api/v1/pharmacy/prescriptions", doctorToken, req)
                .path("rxNo")
                .asText();

        // R2-14 回执链收敛后作废（PENDING_FEE 态 cancel 才走 billing 端口同事务联动）
        awaitRxStatus(rxNo2, "PENDING_FEE");
        List<Map<String, Object>> feeRows2 = awaitFeeRows(rxNo2);
        assertThat(feeRows2).hasSize(1);
        assertThat(feeRows2.get(0).get("status")).isEqualTo("PENDING");

        // 作废（原因必填）→ 处方 CANCELLED+cancel_reason 在；billing 该 sourceRef 费用行 CANCELLED
        ObjectNode cancel = objectMapper.createObjectNode();
        cancel.put("reason", "IT 未缴费作废联动验证");
        assertThat(postForEntity("/api/v1/pharmacy/prescriptions/" + rxNo2 + "/cancel", adminToken, cancel)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Map<String, Object> rxRow = jdbcTemplate.queryForMap(
                "SELECT status, cancel_reason FROM pharmacy.prescription WHERE rx_no = ?", rxNo2);
        assertThat(rxRow.get("status")).isEqualTo("CANCELLED");
        assertThat(String.valueOf(rxRow.get("cancel_reason"))).isEqualTo("IT 未缴费作废联动验证");
        Integer cancelledFees = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_record WHERE source_ref = ?"
                        + " AND trigger_point = 'PRESCRIPTION_EFFECTIVE' AND status = 'CANCELLED'",
                Integer.class,
                rxNo2);
        assertThat(cancelledFees).isEqualTo(1);
    }
}

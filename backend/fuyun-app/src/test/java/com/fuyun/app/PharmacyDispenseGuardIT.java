package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarables;
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
 * PR-4 验收锚点②：守卫面真栈——双签同人拒（PH-1011）、追溯码不一致退药拒（PH-1012）、
 * charged 重复投递幂等（仅放行一次）、批次并发锁定不超发（PH-1010，双线程 CountDownLatch）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PharmacyDispenseGuardIT extends FuyunStackITBase {

    /** 类级独占三容器（容器禁收敛入基类——P1-1 裁决，BillingSettlementFlowIT :62-78 逐字同型；
     *  @DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载，本类不重复声明） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（会话/缓存/幂等前置键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（charged 注入链路依赖；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 运行唯一院内码（防重跑撞 uk_drug_code；A 供双签/防回流两守卫，B 供并发不超发守卫） */
    private static final String DRUG_A = "D-ITG-" + (System.nanoTime() % 1_000_000L);

    private static final String DRUG_B = DRUG_A + "-B";

    /** CF-3 结构合法门诊就诊号（A/B 药品 A 用，C/D 药品 B 用） */
    private static final String VISIT_A = visitId("50001");

    private static final String VISIT_B = visitId("50002");
    private static final String VISIT_C = visitId("50003");
    private static final String VISIT_D = visitId("50004");

    /** 门诊患者主索引（FlowIT 700201 错位，防跨库比对混淆） */
    private static final long PATIENT_ID = 700211L;

    /** 批次账造数行主键（V703 零数据行——IT 经 jdbcTemplate 直插造数，偏差⑧红线；余量恒 2） */
    private static final long BATCH_A_ID = 910211L;

    private static final long BATCH_B_ID = 910212L;

    /** V303 种子超管 id（picker 留痕口径=登录会话 userId 十进制文本，AuthTokenInterceptor:76） */
    private static final long ADMIN_USER_ID = 1L;

    private static String adminToken = "";
    private static String reviewerToken = "";

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String rxA = "";

    private static String rxB = "";
    private static String rxC = "";
    private static String rxD = "";

    private static String dispenseNoA = "";
    private static String prescriptionItemIdA = "";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /** 生成结构合法的 CF-3 就诊号（O + yyyyMMdd + 5 位流水段）。 */
    private static String visitId(String serial5) {
        return "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + serial5;
    }

    /**
     * 事件治理占位（A.5-4：禁止测试自声明交换机/裸队列）：本 IT 仅注入 charged 事件（生产面），
     * 不捕获消费 pharmacy 域事件（守卫断言锚点是响应 errorCode 与 DB 终态），经 MessagingGovernance
     * 声明一只消费队列兜底声明链路（BillingConcurrencyGuardIT ItGovernanceConfig :116-123 同型）。
     */
    @TestConfiguration
    static class ItGovernanceConfig {

        @Bean
        Declarables itGuardQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("itg", PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED));
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
        // 无体响应（void/204 端点）回 MISSING 单例，防 readTree 拒 null 中断用例（FlowIT 同型收口）
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** POST JSON 助手（带 Content-Type/Bearer；守卫用例断状态码改用 postForEntity）。 */
    private JsonNode postJson(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return toNode(restTemplate
                .postForEntity(path, new HttpEntity<>(body, headers), String.class)
                .getBody());
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体 errorCode 断言双取）。 */
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

    /** 配药采集请求体（prescriptionItemId 定位行 + 追溯码逐盒采集，「无码不结」口径）。 */
    private ObjectNode pickBody(String itemId, String... traceCodes) {
        ObjectNode req = objectMapper.createObjectNode();
        ObjectNode line = req.putArray("items").addObject();
        line.put("prescriptionItemId", itemId);
        ArrayNode codes = line.putArray("traceCodes");
        for (String code : traceCodes) {
            codes.add(code);
        }
        return req;
    }

    /**
     * 注入门诊缴费放行事件帧（stub 边界红线：outpatient.order.charged 生产零发布——IT 手工注入
     * 合成信封；每次调用 eventId 均为新 UUID，重投语义由调用方以新 traceId 表达）。
     *
     * @param visitId 放行目标就诊号（消费侧按 visitId 维度放行）
     * @param traceId 全链路追踪号（重投帧以 -replay 后缀区分）
     */
    private void publishCharged(String visitId, String traceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", "ITG-ORDER-" + visitId)
                .put("patientId", PATIENT_ID)
                .put("visitId", visitId);
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

    /** 建项目+定价+发布一步到位（BillingSettlementFlowIT :319-349 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 守卫项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "盒")
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

    /** 药品建档（nhsa 留空——守卫面不涉医保对照；itemCode 与收费项目共码满足可计费开方守卫）。 */
    private long createDrug(String drugCode) {
        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", drugCode)
                .put("genericName", "IT 守卫药品 " + drugCode)
                .put("dosageForm", "片剂")
                .put("specification", "0.5g×2")
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

    /** 批次 SQL 直插（V703 零数据行——IT 造数红线偏差⑧；FEFO 单批足量，近效期序不影响单批场景）。 */
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

    /** 开方（quantity 恒 2——同批次「余量 2 争 4」并发前提）。 */
    private String createRx(String visitId, long drugId) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("patientId", PATIENT_ID).put("visitId", visitId).put("rxType", "OUTPATIENT");
        ObjectNode line = req.putArray("items").addObject();
        line.put("drugId", drugId)
                .put("quantity", "2")
                .put("routeCode", "ORAL")
                .put("frequency", "TID")
                .put("days", 3)
                .put("singleDose", "0.5g");
        return postJson("/api/v1/pharmacy/prescriptions", adminToken, req)
                .path("rxNo")
                .asText();
    }

    /** 读处方当前状态（SQL 直读，轮询与终态断言共用）。 */
    private String rxStatus(String rxNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.prescription WHERE rx_no = ?", String.class, rxNo);
    }

    /** 轮询等待处方到达目标状态（R2-14 回执链收敛等待，上限 10s，禁盲等）。 */
    private void awaitRxStatus(String rxNo, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(rxStatus(rxNo))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("处方状态迁移超时：rxNo=" + rxNo + "，期望 " + expected);
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

    /** pharmacy 已登记 PROCESSED 的 charged 帧数（幂等台账——重投消费完成的非盲等实证锚点）。 */
    private long countChargedProcessed() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_type = 'outpatient.order.charged'"
                        + " AND consumer_module = 'pharmacy' AND status = 'PROCESSED'",
                Long.class);
        return n == null ? 0 : n;
    }

    /** 读批次账数值列（列名仅取本类固定字面量 quantity/locked_qty，禁外部拼接）。 */
    private long batchField(long batchId, String column) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM pharmacy.drug_batch WHERE id = ?", Long.class, batchId);
        return v == null ? -1 : v;
    }

    /**
     * 对齐起跑并发执行（BillingConcurrencyGuardIT runConcurrent :150-176 范式：CountDownLatch 保证
     * 两请求尽量同拍发出，命中并发窗口）。
     *
     * @param actions 各线程执行的 HTTP 动作（线程序即列表序）
     * @return 各线程响应
     * @throws Exception 线程池等待中断/超时（测试基础设施故障显式失败）
     */
    private List<ResponseEntity<String>> runConcurrent(List<Supplier<ResponseEntity<String>>> actions)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (Supplier<ResponseEntity<String>> action : actions) {
            futures.add(pool.submit(() -> {
                start.await();
                return action.get();
            }));
        }
        start.countDown();
        List<ResponseEntity<String>> results = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    @Order(1)
    @DisplayName("双签同人拒：admin 配药后 admin 核对 → 409 PH-1011，再以 reviewer 核对放行")
    void dualSignConflictRejectedAsPh1011() throws Exception {
        // 登录与第二账号播种（it-reviewer 承载核对位，与 admin 互异）
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);

        // 造数基座：两药品各配项目定价（3000 分）+ 批次余量 2 + 四处方（A/B→药品 A，C/D→药品 B）
        newItemWithPrice(DRUG_A, 3000);
        newItemWithPrice(DRUG_B, 3000);
        long drugAId = createDrug(DRUG_A);
        long drugBId = createDrug(DRUG_B);
        insertBatch(BATCH_A_ID, drugAId, "B-ITG-A", 2);
        insertBatch(BATCH_B_ID, drugBId, "B-ITG-B", 2);
        rxA = createRx(VISIT_A, drugAId);
        rxB = createRx(VISIT_B, drugAId);
        rxC = createRx(VISIT_C, drugBId);
        rxD = createRx(VISIT_D, drugBId);
        // R2-14 回执链先行收敛（PENDING_FEE 是 charged 放行谓词前置，禁盲等）
        awaitRxStatus(rxA, "PENDING_FEE");
        awaitRxStatus(rxB, "PENDING_FEE");
        awaitRxStatus(rxC, "PENDING_FEE");
        awaitRxStatus(rxD, "PENDING_FEE");

        // 双 charged 注入（visitA/visitB）→ 放行入队
        publishCharged(VISIT_A, "itg-charged-a");
        publishCharged(VISIT_B, "itg-charged-b");
        JsonNode voA = awaitDispense(rxA);
        dispenseNoA = voA.path("dispenseNo").asText();
        prescriptionItemIdA =
                voA.path("items").get(0).path("prescriptionItemId").asText();
        awaitDispense(rxB);

        // 配药（admin 调配位）：批次 A 锁定 2 + 追溯码逐盒采集
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispenses/" + dispenseNoA + "/pick",
                                adminToken,
                                pickBody(prescriptionItemIdA, "TR-ITG-001", "TR-ITG-002"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);

        // 同人核对（admin 核对 admin 调配）→ 409 PH-1011（Spec :226 双签分权后端硬守卫）
        ResponseEntity<String> conflict =
                postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNoA + "/verify", adminToken);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(toNode(conflict.getBody()).path("errorCode").asText())
                .as("同人双签应拒 PH-1011")
                .isEqualTo("PH-1011");
        // 守卫前置即拒：单据停留 PICKING 未发生状态迁移
        assertThat(getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxA, adminToken)
                        .get(0)
                        .path("status")
                        .asText())
                .isEqualTo("PICKING");

        // 异人核对放行（reviewer 承载核对位）→ 2xx 且留痕正确
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNoA + "/verify", reviewerToken)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        JsonNode verified =
                getJson("/api/v1/pharmacy/dispenses?rxNo=" + rxA, adminToken).get(0);
        assertThat(verified.path("status").asText()).isEqualTo("PICKED");
        assertThat(verified.path("picker").asText()).isEqualTo(String.valueOf(ADMIN_USER_ID));
        assertThat(verified.path("verifier").asText()).isEqualTo(String.valueOf(REVIEWER_USER_ID));
    }

    @Test
    @Order(2)
    @DisplayName("防回流药：退药追溯码与发药记录不一致 → 409 PH-1012 且批次零变化")
    void returnWithForgedTraceCodeRejectedAsPh1012() throws Exception {
        // 前置：Order(1) 留下的 PICKED 单发药签名（PICKED→ISSUED，锁定转扣减：批次 A 2→0）
        assertThat(postEmpty("/api/v1/pharmacy/dispenses/" + dispenseNoA + "/issue", adminToken)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(rxStatus(rxA)).isEqualTo("DISPENSED");
        long quantityBefore = batchField(BATCH_A_ID, "quantity");
        long lockedBefore = batchField(BATCH_A_ID, "locked_qty");
        Integer ledgerBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE batch_id = ? AND deleted = 0",
                Integer.class,
                BATCH_A_ID);

        // 伪码退药受理（TR-FAKE ∉ 发药采集码集）→ 409 PH-1012（防回流药逐码核验）
        ObjectNode ret = objectMapper.createObjectNode();
        ret.put("dispenseNo", dispenseNoA).put("mode", "ISSUED_RETURN");
        ObjectNode retLine = ret.putArray("items").addObject();
        retLine.put("prescriptionItemId", prescriptionItemIdA).put("returnQuantity", "2");
        retLine.putArray("traceCodes").add("TR-FAKE");
        ResponseEntity<String> resp = postForEntity("/api/v1/pharmacy/dispense-returns", adminToken, ret);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText())
                .as("伪码退药应拒 PH-1012")
                .isEqualTo("PH-1012");

        // 批次零变化：数量/锁定数原值 + 无新增冲正流水（守卫前置即拒零副作用）
        assertThat(batchField(BATCH_A_ID, "quantity")).isEqualTo(quantityBefore);
        assertThat(batchField(BATCH_A_ID, "locked_qty")).isEqualTo(lockedBefore);
        Integer ledgerAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE batch_id = ? AND deleted = 0",
                Integer.class,
                BATCH_A_ID);
        assertThat(ledgerAfter).isEqualTo(ledgerBefore);
    }

    @Test
    @Order(3)
    @DisplayName("charged 重复投递幂等：新 eventId 同 visitId 重投，发药单仍恰一张")
    void chargedRedeliveryIsIdempotent() throws Exception {
        // visitB 已在 Order(1) 放行入队（恰一张活动单）——同 visitId 新 eventId 双帧重投
        long processedBefore = countChargedProcessed();
        publishCharged(VISIT_B, "itg-charged-b-replay-1");
        publishCharged(VISIT_B, "itg-charged-b-replay-2");

        // 非盲等实证：轮询幂等台账直至两帧均被 pharmacy 消费登记 PROCESSED（消费完成后才登记）
        for (int i = 0; i < 100; i++) {
            if (countChargedProcessed() >= processedBefore + 2) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(countChargedProcessed()).as("两帧重投均应被消费登记").isGreaterThanOrEqualTo(processedBefore + 2);
        // 覆盖迟到帧残余窗口（FlowIT Order(3) 同型有界兜底）
        Thread.sleep(2000);
        assertThat(countDispense(rxB)).as("charged 重复投递仅放行一次：发药单仍恰一张").isEqualTo(1);
        assertThat(rxStatus(rxB)).isEqualTo("PENDING_DISPENSE");
    }

    @Test
    @Order(4)
    @DisplayName("批次并发不超发：两处方并发配药同批次（余量 2 争 4）恰一成功一 PH-1010")
    void concurrentPickDoesNotOversellBatch() throws Exception {
        // visitC/visitD 放行入队（同批次 B 余量 2，两处方各请发 2——争 4 超发前提）
        publishCharged(VISIT_C, "itg-charged-c");
        publishCharged(VISIT_D, "itg-charged-d");
        JsonNode voC = awaitDispense(rxC);
        JsonNode voD = awaitDispense(rxD);
        String dispenseNoC = voC.path("dispenseNo").asText();
        String dispenseNoD = voD.path("dispenseNo").asText();
        String itemIdC = voC.path("items").get(0).path("prescriptionItemId").asText();
        String itemIdD = voD.path("items").get(0).path("prescriptionItemId").asText();

        // 对齐起跑双线程各自 pick（同批次竞争； CountDownLatch 对齐发拍）
        List<ResponseEntity<String>> results = runConcurrent(List.of(
                () -> postForEntity(
                        "/api/v1/pharmacy/dispenses/" + dispenseNoC + "/pick",
                        adminToken,
                        pickBody(itemIdC, "TR-ITG-C01", "TR-ITG-C02")),
                () -> postForEntity(
                        "/api/v1/pharmacy/dispenses/" + dispenseNoD + "/pick",
                        adminToken,
                        pickBody(itemIdD, "TR-ITG-D01", "TR-ITG-D02"))));

        // 恰一 2xx 一 409 PH-1010（批次可用量不足硬防线：条件更新未命中即拒）
        long okCount = results.stream()
                .filter(r -> r.getStatusCode().is2xxSuccessful())
                .count();
        long rejectedCount = results.stream()
                .filter(r -> r.getStatusCode().value() == 409)
                .filter(r -> toNode(r.getBody()).path("errorCode").asText().equals("PH-1010"))
                .count();
        assertThat(okCount).as("恰一配药成功").isEqualTo(1);
        assertThat(rejectedCount).as("恰一 PH-1010 超发拒绝").isEqualTo(1);

        // 批次勾稽：成功侧恰锁定 2、现存量未动（余量 2 不因并发争抢被超发）
        assertThat(batchField(BATCH_B_ID, "locked_qty")).isEqualTo(2L);
        assertThat(batchField(BATCH_B_ID, "quantity")).isEqualTo(2L);
        // 单据终态：恰一张 PICKING（赢家）+ 一张 CREATED（输家整体事务回滚）
        String statusC =
                jdbcTemplate.queryForObject("SELECT status FROM pharmacy.dispense WHERE rx_no = ?", String.class, rxC);
        String statusD =
                jdbcTemplate.queryForObject("SELECT status FROM pharmacy.dispense WHERE rx_no = ?", String.class, rxD);
        assertThat(List.of(statusC, statusD))
                .as("恰一 PICKING 一 CREATED（赢家锁定续行，输家回滚待重配）")
                .containsExactlyInAnyOrder("PICKING", "CREATED");
        // 零出库流水（未到发药签名，锁定数不属数量流水）
        Integer ledgerRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pharmacy.stock_ledger WHERE batch_id = ? AND deleted = 0",
                Integer.class,
                BATCH_B_ID);
        assertThat(ledgerRows).isZero();
    }
}

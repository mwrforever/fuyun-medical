package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * 结算/退费并发收口验收 IT（2026-09-18 用户裁决：PR-3 三项资金缺口本 PR 修复；PR-4 W-16/W-18 收口扩场景）：
 * 真栈 Testcontainers（HTTP/MQ/DB 零 mock）下以 CountDownLatch 对齐起跑的双线程并发，验证
 * ①同单并发双 settle 恰一赢一幂等直返（CAS 抢锚，无并发双 PAY）；②并发双 apply 同费用
 * 超可退恰一成功一 BILL-1021（行锁内重读聚合守卫）；③同卡拆分两行结算后 execute 单次入账
 * （channelRef 聚合，无重复贷记）；④并发双发 execute 恰一执行一幂等直返（W-16 CAS 抢 EXECUTED
 * 锚，REFUND 台账恰一笔）；⑤跨结算单费用行 apply 被 409 BILL-1031 拒（W-18 归属守卫）。
 * 每并发场景重复 3 轮防 flaky，断言 DB 终态与台账行数不绑实现细节。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingConcurrencyGuardIT extends FuyunStackITBase {

    /** 类级独占三容器（容器禁收敛入基类——P1-1 裁决，BillingSettlementFlowIT 同款形态） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（会话/缓存/幂等前置键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（开单事件注入链路依赖；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 运行唯一项目编码（防重跑撞 uk_charge_item_code；两项目=一单两行造数，同 FlowIT 口径） */
    private static final String ITEM_CODE = "C-ITX-" + (System.nanoTime() % 1_000_000L);

    private static final String ITEM_CODE2 = ITEM_CODE + "-2";

    /** 门诊患者主索引（与卡账户造数行同源绑定） */
    private static final long PATIENT_ID = 700909L;

    /** 卡账户造数行主键（初始余额 1000000 分，三场景轮次内收付净额远小于余额） */
    private static final long CARD_ACCOUNT_ID = 990909L;

    /** 就诊号流水段计数器（每轮递增保证 CF-3 就诊号全 IT 唯一） */
    private static final AtomicInteger VISIT_SERIAL = new AtomicInteger(100);

    /** 并发轮次（每场景重复 3 次防 flaky） */
    private static final int ROUNDS = 3;

    private static String adminToken = "";

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 生成结构合法且全 IT 唯一的 CF-3 门诊就诊号（O + yyyyMMdd + 5 位流水段）。 */
    private static String nextVisitId() {
        return "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", VISIT_SERIAL.incrementAndGet() % 100000);
    }

    /**
     * 事件治理占位（A.5-4：禁止测试自声明交换机/裸队列）：本 IT 仅注入开单事件（生产面），
     * 不捕获消费 settlement.completed/refund.approved（并发断言锚点是 DB 终态与台账行数），
     * 但 fy.topic 声明仍须由治理构件完成，经 MessagingGovernance 声明一只消费队列兜底声明链路。
     */
    @TestConfiguration
    static class ItGovernanceConfig {

        @Bean
        org.springframework.amqp.core.Declarables itGuardQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(new ConsumerQueueSpec("itx", "billing.settlement.completed"));
        }
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体并发断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 带令牌 GET JSON（轮询读费用/结算出参）。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return toNode(restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody());
    }

    private JsonNode toNode(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 对齐起跑并发执行（每线程同一动作；CountDownLatch 保证两请求尽量同拍发出，命中并发窗口）。
     *
     * @param threads 并发线程数（本 IT 恒 2：双 settle / 双 apply）
     * @param action  各线程执行的 HTTP 动作
     * @return 各线程响应（线程序即列表序）
     * @throws Exception 线程池等待中断/超时（测试基础设施故障显式失败）
     */
    private List<ResponseEntity<String>> runConcurrent(int threads, Supplier<ResponseEntity<String>> action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
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

    /** 注入门诊开单事件帧（一单两行：ITEM_CODE×1 + ITEM_CODE2×1 = 5000 分，双费用行勾稽口径）。 */
    private void publishOrderEvent(String orderId, String visitId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId).put("patientId", PATIENT_ID).put("visitId", visitId);
        ArrayNode lines = payload.putArray("lines");
        lines.addObject().put("itemCode", ITEM_CODE).put("quantity", 1);
        lines.addObject().put("itemCode", ITEM_CODE2).put("quantity", 1);
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "outpatient.order.created",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "outpatient",
                        "outpatient.order.created",
                        "it-guard-" + orderId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    /** 轮询等待该就诊 PENDING 费用足量（事件驱动异步，上限 10s，FlowIT awaitFees 同款）。 */
    private void awaitFees(String visitId, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode page = getJson("/api/v1/billing/fees?visitId=" + visitId, adminToken);
            if (page.path("content").size() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：visit=" + visitId + "，期望 " + expected + " 行");
    }

    /** 预结算并返回 settleNo（自费 CASH 草稿；场景轮次通用前置）。 */
    private String previewSelfPay(String visitId) {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", visitId).put("payerType", "SELF_PAY");
        return toNode(postForEntity("/api/v1/billing/settlements/preview", adminToken, preview)
                        .getBody())
                .path("settleNo")
                .asText();
    }

    /** 建项目+定价+发布（FlowIT newItemWithPrice 同款三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 并发收口项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        long itemId = toNode(postForEntity("/api/v1/billing/charge-items", adminToken, item)
                        .getBody())
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
        long priceRowId = toNode(postForEntity("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                        .getBody())
                .asLong();
        postForEntity(
                "/api/v1/billing/price-adjustments/" + priceRowId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
    }

    @Test
    @Order(1)
    @DisplayName("前置：登录、两项目定价发布、造卡账户（余额 1000000 分）")
    void prepareItemsPriceAndCardAccount() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        newItemWithPrice(ITEM_CODE, 3000);
        newItemWithPrice(ITEM_CODE2, 2000);
        // 卡账户造数（M02 无 HTTP 开户端点，CardAccountLedger 仅进程内调用；uk 一人一账户幂等兜底）
        jdbcTemplate.update(
                "INSERT INTO patient.card_account (id, patient_id, balance, status)"
                        + " SELECT ?, ?, 1000000, 'ACTIVE'"
                        + " WHERE NOT EXISTS (SELECT 1 FROM patient.card_account WHERE patient_id = ?)",
                CARD_ACCOUNT_ID,
                PATIENT_ID,
                PATIENT_ID);
        Integer balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM patient.card_account WHERE id = ?", Integer.class, CARD_ACCOUNT_ID);
        assertThat(balance).isEqualTo(1000000);
    }

    @Test
    @Order(2)
    @DisplayName("场景①同单并发双 settle：恰一赢一幂等直返同 settleNo，费用单次迁移、卡台账 PAY 恰一行")
    void concurrentSettleOnSameSettlementKeepsSinglePayLedgerRow() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String visitId = nextVisitId();
            publishOrderEvent("ITX-S1-" + round, visitId);
            awaitFees(visitId, 2);
            String settleNo = previewSelfPay(visitId);
            ObjectNode settle = objectMapper.createObjectNode();
            settle.put("settleNo", settleNo);
            settle.putArray("payments")
                    .addObject()
                    .put("method", "CARD_BALANCE")
                    .put("amount", "5000")
                    .put("channelRef", String.valueOf(CARD_ACCOUNT_ID));

            // 对齐起跑双 settle（绕 UI 并发面：直接双 HTTP 同拍）
            List<ResponseEntity<String>> results =
                    runConcurrent(2, () -> postForEntity("/api/v1/billing/settlements", adminToken, settle));

            // 双请求均 200 且同 settleNo 同终态：一个真实结算、一个 CAS 输家幂等直返（原缺口双 PAY 双扣）
            for (ResponseEntity<String> resp : results) {
                assertThat(resp.getStatusCode().value()).isEqualTo(200);
                JsonNode body = toNode(resp.getBody());
                assertThat(body.path("settleNo").asText()).isEqualTo(settleNo);
                assertThat(body.path("status").asText()).isEqualTo("SETTLED");
            }
            // DB 终态：结算单唯一行 SETTLED、两费用行单次迁移（无双单双扣中间态）
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM billing.settlement WHERE settle_no = ? AND status = 'SETTLED'",
                            Integer.class,
                            settleNo))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND status = 'SETTLED'",
                            Integer.class,
                            visitId))
                    .isEqualTo(2);
            // 台账断言：该单 PAY 出账恰一行 5000 分（并发双扣即两行，修复后不可能）
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM patient.card_txn WHERE account_id = ? AND txn_type = 'PAY'"
                                    + " AND biz_ref = ?",
                            Integer.class,
                            CARD_ACCOUNT_ID,
                            settleNo))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT amount FROM patient.card_txn WHERE account_id = ? AND txn_type = 'PAY' AND biz_ref = ?",
                            Long.class,
                            CARD_ACCOUNT_ID,
                            settleNo))
                    .isEqualTo(5000L);
        }
    }

    @Test
    @Order(3)
    @DisplayName("场景②并发双 apply 同费用超可退：恰一成功一 BILL-1021，退费单恰一行（行锁内守卫拒）")
    void concurrentApplyOnSameFeeAllowsOnlyOneRefund() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String visitId = nextVisitId();
            publishOrderEvent("ITX-S2-" + round, visitId);
            awaitFees(visitId, 2);
            String settleNo = previewSelfPay(visitId);
            ObjectNode settle = objectMapper.createObjectNode();
            settle.put("settleNo", settleNo);
            settle.putArray("payments").addObject().put("method", "CASH").put("amount", "5000");
            assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            List<Long> feeIds = jdbcTemplate.queryForList(
                    "SELECT id FROM billing.fee_record WHERE visit_id = ? ORDER BY id", Long.class, visitId);
            assertThat(feeIds).hasSize(2);
            Long settlementId = jdbcTemplate.queryForObject(
                    "SELECT settlement_id FROM billing.fee_record WHERE id = ?", Long.class, feeIds.get(0));

            // 双申请同请求体：全量退两行（5000 分 ≤ 免审阈值 50000，当日 → 原缺口双双自动批准路径）
            ObjectNode apply = objectMapper.createObjectNode();
            apply.put("settlementId", settlementId).put("reason", "IT 并发双申请超可退验证");
            ArrayNode lines = apply.putArray("lines");
            lines.addObject().put("feeId", feeIds.get(0)).put("refundQuantity", "1");
            lines.addObject().put("feeId", feeIds.get(1)).put("refundQuantity", "1");
            List<ResponseEntity<String>> results =
                    runConcurrent(2, () -> postForEntity("/api/v1/billing/refunds", adminToken, apply));

            // 恰一成功一拒：成功侧 201 出退费 id（POST /refunds 冻结契约）；拒绝侧 409 BILL-1021
            //   （行锁内重读已退聚合超上限——修复前双读聚合互不可见双双过守卫）
            long okCount = results.stream()
                    .filter(r -> r.getStatusCode().value() == 201)
                    .count();
            long rejectedCount = results.stream()
                    .filter(r -> r.getStatusCode().value() == 409
                            && toNode(r.getBody()).path("errorCode").asText().equals("BILL-1021"))
                    .count();
            assertThat(okCount).isEqualTo(1);
            assertThat(rejectedCount).isEqualTo(1);
            // DB 终态：该结算单退费申请恰一行（并发重复申请即两行，修复后不可能）
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM billing.refund_request WHERE settlement_id = ?",
                            Integer.class,
                            settlementId))
                    .isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    @DisplayName("场景③同卡拆分两行结算后 execute：台账 REFUND 恰一行全额（channelRef 聚合单次入账）")
    void executeAfterSplitCardPaymentCreditsRefundOnce() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String visitId = nextVisitId();
            publishOrderEvent("ITX-S3-" + round, visitId);
            awaitFees(visitId, 2);
            String settleNo = previewSelfPay(visitId);
            // 同卡拆分两行（写入侧守卫允许：channelRef 一致求和扣款）——修复前 execute 逐行全额贷记两倍入账
            ObjectNode settle = objectMapper.createObjectNode();
            settle.put("settleNo", settleNo);
            ArrayNode payments = settle.putArray("payments");
            payments.addObject()
                    .put("method", "CARD_BALANCE")
                    .put("amount", "2000")
                    .put("channelRef", String.valueOf(CARD_ACCOUNT_ID));
            payments.addObject()
                    .put("method", "CARD_BALANCE")
                    .put("amount", "3000")
                    .put("channelRef", String.valueOf(CARD_ACCOUNT_ID));
            assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            Long settlementId = jdbcTemplate.queryForObject(
                    "SELECT id FROM billing.settlement WHERE settle_no = ?", Long.class, settleNo);
            List<Long> feeIds = jdbcTemplate.queryForList(
                    "SELECT id FROM billing.fee_record WHERE visit_id = ? ORDER BY id", Long.class, visitId);

            // 免审直退（5000 ≤ 50000 当日）→ apply 即 APPROVED → execute 原路退回
            ObjectNode apply = objectMapper.createObjectNode();
            apply.put("settlementId", settlementId).put("reason", "IT 同卡拆分退费验证");
            ArrayNode lines = apply.putArray("lines");
            lines.addObject().put("feeId", feeIds.get(0)).put("refundQuantity", "1");
            lines.addObject().put("feeId", feeIds.get(1)).put("refundQuantity", "1");
            long refundId = toNode(postForEntity("/api/v1/billing/refunds", adminToken, apply)
                            .getBody())
                    .asLong();
            // execute 冻结契约 204 无体（POST /refunds/{id}/execute）
            assertThat(postForEntity(
                                    "/api/v1/billing/refunds/" + refundId + "/execute",
                                    adminToken,
                                    objectMapper.createObjectNode())
                            .getStatusCode()
                            .value())
                    .isEqualTo(204);
            String refundNo = jdbcTemplate.queryForObject(
                    "SELECT refund_no FROM billing.refund_request WHERE id = ?", String.class, refundId);

            // 台账断言：该退费单 REFUND 入账恰一行且金额=全额 5000（同卡两行聚合单次贷记；
            //   结算 PAY 亦恰一行 5000——拆分行求和一笔出账口径）
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM patient.card_txn WHERE account_id = ? AND txn_type = 'REFUND'"
                                    + " AND biz_ref = ?",
                            Integer.class,
                            CARD_ACCOUNT_ID,
                            refundNo))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT amount FROM patient.card_txn WHERE account_id = ? AND txn_type = 'REFUND' AND biz_ref = ?",
                            Long.class,
                            CARD_ACCOUNT_ID,
                            refundNo))
                    .isEqualTo(5000L);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM patient.card_txn WHERE account_id = ? AND txn_type = 'PAY'"
                                    + " AND biz_ref = ?",
                            Integer.class,
                            CARD_ACCOUNT_ID,
                            settleNo))
                    .isEqualTo(1);
            // 余额勾稽：本轮净额=0（PAY 5000 出 + REFUND 5000 入），不因重复入账漂移
            Long balanceAfter = jdbcTemplate.queryForObject(
                    "SELECT balance FROM patient.card_account WHERE id = ?", Long.class, CARD_ACCOUNT_ID);
            Long expected = 1000000L - 5000L * (long) ROUNDS /* 场景①三轮各 PAY 5000 不退 */;
            assertThat(balanceAfter).isEqualTo(expected);
        }
    }

    @Test
    @Order(10)
    @DisplayName("W-16 并发双发 execute：CAS 锚恰一执行、输家幂等直返，REFUND 台账恰一笔")
    void concurrentDoubleExecuteHasSingleLedgerEntry() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            String visitId = nextVisitId();
            publishOrderEvent("ITX-S4-" + round, visitId);
            awaitFees(visitId, 2);
            String settleNo = previewSelfPay(visitId);
            ObjectNode settle = objectMapper.createObjectNode();
            settle.put("settleNo", settleNo);
            settle.putArray("payments")
                    .addObject()
                    .put("method", "CARD_BALANCE")
                    .put("amount", "5000")
                    .put("channelRef", String.valueOf(CARD_ACCOUNT_ID));
            assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            Long settlementId = jdbcTemplate.queryForObject(
                    "SELECT id FROM billing.settlement WHERE settle_no = ?", Long.class, settleNo);
            List<Long> feeIds = jdbcTemplate.queryForList(
                    "SELECT id FROM billing.fee_record WHERE visit_id = ? ORDER BY id", Long.class, visitId);

            // 免审直退（5000 ≤ 免审阈值 50000 当日）→ apply 即 APPROVED，取退费单 id
            ObjectNode apply = objectMapper.createObjectNode();
            apply.put("settlementId", settlementId).put("reason", "IT 并发双执行幂等验证");
            ArrayNode lines = apply.putArray("lines");
            lines.addObject().put("feeId", feeIds.get(0)).put("refundQuantity", "1");
            lines.addObject().put("feeId", feeIds.get(1)).put("refundQuantity", "1");
            long refundId = toNode(postForEntity("/api/v1/billing/refunds", adminToken, apply)
                            .getBody())
                    .asLong();

            // 对齐起跑双 execute（绕 UI 并发面：直接双 HTTP 同拍）：CAS 锚下恰一执行一幂等直返
            List<ResponseEntity<String>> results = runConcurrent(
                    2,
                    () -> postForEntity(
                            "/api/v1/billing/refunds/" + refundId + "/execute",
                            adminToken,
                            objectMapper.createObjectNode()));

            // 双响应皆 2xx：赢家 204 真执行、输家 204 幂等直返（修复前双贷记两笔 REFUND）
            for (ResponseEntity<String> resp : results) {
                assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            }
            // DB 终态：该退费单恰一行且 EXECUTED（轮询上限 10s 兜底——输家 CAS 语义上晚于赢家提交返回）
            boolean executed = false;
            for (int i = 0; i < 100 && !executed; i++) {
                Integer rows = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM billing.refund_request WHERE id = ? AND status = 'EXECUTED'",
                        Integer.class,
                        refundId);
                executed = rows != null && rows == 1;
                if (!executed) {
                    Thread.sleep(100);
                }
            }
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM billing.refund_request WHERE id = ? AND status = 'EXECUTED'",
                            Integer.class,
                            refundId))
                    .isEqualTo(1);
            // 台账断言：REFUND 入账恰一行且金额=全额 5000（双发仅赢家一笔贷记；退额 ≤ 卡侧原付 5000 过 W-16 F6 守卫）
            String refundNo = jdbcTemplate.queryForObject(
                    "SELECT refund_no FROM billing.refund_request WHERE id = ?", String.class, refundId);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM patient.card_txn WHERE account_id = ? AND txn_type = 'REFUND'"
                                    + " AND biz_ref = ?",
                            Integer.class,
                            CARD_ACCOUNT_ID,
                            refundNo))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT amount FROM patient.card_txn WHERE account_id = ? AND txn_type = 'REFUND' AND biz_ref = ?",
                            Long.class,
                            CARD_ACCOUNT_ID,
                            refundNo))
                    .isEqualTo(5000L);
        }
    }

    @Test
    @Order(11)
    @DisplayName("W-18 顺序守卫：跨结算单费用行 apply 被 409 BILL-1031 拒（BILL-1030 归单测面承载）")
    void applyWithForeignFeeRejectedAsBill1031() throws Exception {
        // 造数 A：常规开单→CASH 结算（SETTLED）——退费申请的归属结算单
        String visitA = nextVisitId();
        publishOrderEvent("ITX-S5-A", visitA);
        awaitFees(visitA, 2);
        String settleNoA = previewSelfPay(visitA);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", settleNoA);
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "5000");
        assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Long settlementIdA = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.settlement WHERE settle_no = ?", Long.class, settleNoA);

        // 造数 B：仅开单不结算——费用行 PENDING 且 settlement_id 为空（跨单外行）
        String visitB = nextVisitId();
        publishOrderEvent("ITX-S5-B", visitB);
        awaitFees(visitB, 2);
        Long foreignFeeId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.fee_record WHERE visit_id = ? ORDER BY id LIMIT 1", Long.class, visitB);

        // 归属结算单 A（SETTLED）+ 外单费用行 B：行 settlement_id（空）≠ A → 归属守卫先命中 BILL-1031
        ObjectNode apply = objectMapper.createObjectNode();
        apply.put("settlementId", settlementIdA).put("reason", "IT 跨结算单拼行退费守卫验证");
        apply.putArray("lines").addObject().put("feeId", foreignFeeId).put("refundQuantity", "1");
        ResponseEntity<String> resp = postForEntity("/api/v1/billing/refunds", adminToken, apply);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("BILL-1031");
        // DB 终态：守卫前置即拒，申请单零落库
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM billing.refund_request WHERE settlement_id = ?",
                        Integer.class,
                        settlementIdA))
                .isZero();
    }
}

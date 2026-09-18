package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 结算闭环验收锚点 IT（PLAN-P1-01 §PR-3 验收行①）：门诊开单事件→PENDING 费用（快照冻结）→
 * 重复投递幂等→预结算→正式结算（settlement.completed 事件可消费）→退费（免审直退 + 一级审批 +
 * 二级审批三路径，refund.approved 事件可消费）全链真栈（HTTP/MQ/DB 零 mock）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingSettlementFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（第 2 轮审查 P1-1 裁决：容器禁收敛入基类——共享 broker 会令跨 IT 手工
     *  捕获队列互相抢消息串扰；每 IT 独占一套、tag 与 compose 严格一致，EmpiGovernanceIT :74-90 同款形态；
     *  @DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载，本类不重复声明） */
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

    /** 运行唯一项目编码（防重跑撞 uk_charge_item_code；两项目=一单两行造数） */
    private static final String ITEM_CODE = "C-IT-" + (System.nanoTime() % 1_000_000L);

    private static final String ITEM_CODE2 = ITEM_CODE + "-2";
    /** 二级审批大额项目编码（单行 3000 分 × 70 = 210000 分 > singleApprovalFen 200000） */
    private static final String LARGE_ITEM_CODE = ITEM_CODE + "-LARGE";
    /** CF-3 结构合法门诊就诊号（三笔：免审路径 / 一级审批路径 / 二级审批路径） */
    private static final String VISIT_A = visitId("00001");

    private static final String VISIT_B = visitId("00002");
    private static final String VISIT_C = visitId("00003");
    private static final long PATIENT_ID = 700101L;

    /** 二级审批账号（财务/医保办侧；IT 就地播种，与申请人/一级审批人三方互异） */
    private static final String REVIEWER2_LOGIN_NAME = "it-reviewer2";

    private static final long REVIEWER2_USER_ID = 3L;

    private static String adminToken = "";
    private static String reviewerToken = "";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /** 生成结构合法的 CF-3 就诊号（O + yyyyMMdd + 5 位流水段）。 */
    private static String visitId(String serial5) {
        return "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + serial5;
    }

    /**
     * 捕获队列声明（A.5-4 治理红线：禁止测试自声明交换机/裸队列——照 EmpiGovernanceIT
     * ItQueueConfiguration 先例经 MessagingGovernance 声明，@Bean 返回 Declarables 交 RabbitAdmin
     * 幂等声明，直调 declareConsumerQueue 丢弃返回值不会声明队列）。一事件一队列：
     * q.it.billing.settlement.completed / q.it.billing.refund.approved（q.<消费模块>.<事件三段名>，
     * 与构件命名规则同源，禁手写字面量）。声明副作用 registerSubscriber 会把 "it" 追加进 V605 两
     * 事件的 subscriber_modules——事件已在 V605 id 19/20 登记（非 broadcast 标记行），「it」作消费方
     * 模块标识仅进订阅清单不改登记行，与 Empi 先例同款、IT 环境可接受；raw 解析监听不进幂等台账。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_SETTLEMENT =
                MessagingConstants.QUEUE_PREFIX + "it." + BillingMessagingConstants.EVENT_SETTLEMENT_COMPLETED;
        static final String Q_REFUND =
                MessagingConstants.QUEUE_PREFIX + "it." + BillingMessagingConstants.EVENT_REFUND_APPROVED;
        static final CountDownLatch SETTLEMENT_LATCH = new CountDownLatch(1);
        static final CountDownLatch REFUND_LATCH = new CountDownLatch(1);
        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itSettlementCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", BillingMessagingConstants.EVENT_SETTLEMENT_COMPLETED));
        }

        @Bean
        Declarables itRefundCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", BillingMessagingConstants.EVENT_REFUND_APPROVED));
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
        @RabbitListener(queues = {ItCaptureConfig.Q_SETTLEMENT, ItCaptureConfig.Q_REFUND})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItCaptureConfig.CAPTURED.add(envelope);
            if ("billing.settlement.completed".equals(envelope.eventType())) {
                ItCaptureConfig.SETTLEMENT_LATCH.countDown();
            } else if ("billing.refund.approved".equals(envelope.eventType())) {
                ItCaptureConfig.REFUND_LATCH.countDown();
            }
        }
    }

    /** 带 Bearer 的 GET 助手（分页/详情共用；形态照抄 EmpiGovernanceIT 私有 getForJson）。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

    private JsonNode toNode(String body) {
        // 实测收口：POST /price-adjustments/{id}/publish 为冻结契约 204 无体端点（Task 12 定稿），
        // 响应体 null 直入 readTree 抛「argument content is null」中断用例——无体响应回 MISSING 单例，
        // 仅对 204 端点忽略返回值；带体断言路径行为零变化（null 响应意外缺体时 path() 取 missing，断言仍可定位）
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode postJson(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return toNode(restTemplate
                .postForEntity(path, new HttpEntity<>(body, headers), String.class)
                .getBody());
    }

    private void putToken() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
    }

    /**
     * 就地播种第三账号（二级审批人；照 FuyunStackITBase.seedReviewerUser 形态：口令哈希直取 admin 行，
     * 复用 ADMIN 角色绑定）。二级审批链要求申请/一级/二级三方账号互异，本账号作二级终批人。
     */
    private void seedReviewer2User() {
        String adminHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM system.sys_user WHERE login_name = ?", String.class, ADMIN_LOGIN_NAME);
        jdbcTemplate.update(
                "INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)"
                        + " SELECT ?, ?, ?, 'STAFF', 'ACTIVE'"
                        + " WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = ?)",
                REVIEWER2_USER_ID,
                REVIEWER2_LOGIN_NAME,
                adminHash,
                REVIEWER2_LOGIN_NAME);
        jdbcTemplate.update(
                "INSERT INTO system.sys_user_role (id, user_id, role_id)"
                        + " SELECT ?, ?, 1"
                        + " WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = ? AND role_id = 1)",
                REVIEWER2_USER_ID,
                REVIEWER2_USER_ID,
                REVIEWER2_USER_ID);
    }

    /** 注入门诊开单事件帧（通用单行形态：指定项目与数量，二级审批大额造数用）。 */
    private void publishOrderEvent(String orderId, String visitId, String itemCode, int quantity) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId).put("patientId", PATIENT_ID).put("visitId", visitId);
        payload.putArray("lines").addObject().put("itemCode", itemCode).put("quantity", quantity);
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "outpatient.order.created",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "outpatient",
                        "outpatient.order.created",
                        "it-flow-" + orderId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    /**
     * 轮询等待指定退费单的 refund.approved 事件到达（事件时点=终批的实证锚点；上限 10s）。
     *
     * @param refundIdText 退费单 id 文本（载荷内 Long 经全局 Long→String 序列化为文本）
     * @return 命中的事件信封（首条）
     */
    private EventEnvelope awaitRefundApprovedEvent(String refundIdText) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            EventEnvelope hit = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> "billing.refund.approved".equals(e.eventType()))
                    .filter(e ->
                            refundIdText.equals(e.payload().path("refundId").asText()))
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                return hit;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("退费终批事件超时：refundId=" + refundIdText);
    }

    /** 注入门诊开单事件帧（一单两行：3000×2 + 2000×1 = 8000 分）。 */
    private void publishOrderEvent(String orderId, String visitId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId).put("patientId", PATIENT_ID).put("visitId", visitId);
        ArrayNode lines = payload.putArray("lines");
        lines.addObject().put("itemCode", ITEM_CODE).put("quantity", 2);
        lines.addObject().put("itemCode", ITEM_CODE2).put("quantity", 1);
        EventEnvelope envelope = envelopeCodec.create(
                Clock.systemUTC(),
                "outpatient",
                "outpatient.order.created",
                "it-flow-" + orderId,
                objectMapper.convertValue(payload, Map.class));
        rabbitTemplate.convertAndSend("fy.topic", "outpatient.order.created", envelope);
    }

    /** 轮询等待该就诊 PENDING 费用足量（事件驱动异步，上限 10s）。 */
    private JsonNode awaitFees(String visitId, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode page = getJson("/api/v1/billing/fees?visitId=" + visitId, adminToken);
            if (page.path("content").size() >= expected) {
                return page;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：visit=" + visitId + "，期望 " + expected + " 行");
    }

    @Test
    @Order(1)
    @DisplayName("前置：双账号登录与播种；建两项目（3000/2000 分）定价并发布生效")
    void prepareItemAndPrice() {
        putToken();
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);

        long itemA = newItemWithPrice(ITEM_CODE, 3000);
        newItemWithPrice(ITEM_CODE2, 2000);

        // 数据库写操作核验：版本置 PUBLISHED 且当前唯一（部分唯一索引语义）
        Integer published = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.charge_item_price WHERE charge_item_id = ? AND status = 'PUBLISHED' AND effective_to IS NULL",
                Integer.class,
                itemA);
        assertThat(published).isEqualTo(1);
    }

    /** 建项目+定价+发布一步到位（POST /charge-items → /charge-items/{id}/prices → /price-adjustments/{id}/publish）。 */
    private long newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 收费项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        // 实测收口：POST /charge-items 直出 ChargeItemVO（非裸 id，Task 12 冻结形态）——取 .id 键后续链
        long itemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（saveDraft 按码取项目，缺字段整请求 400）
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
        return itemId;
    }

    @Test
    @Order(2)
    @DisplayName("开单事件→两行 PENDING 费用：一单两行逐行展开、金额服务端按快照算")
    void orderEventGeneratesPendingFeesWithSnapshot() throws InterruptedException {
        publishOrderEvent("ORD-IT-A", VISIT_A);
        JsonNode page = awaitFees(VISIT_A, 2);
        JsonNode first = page.path("content").get(0);
        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("unitPriceSnapshot").asLong()).isEqualTo(3000L);
        assertThat(first.path("priceVersion").asInt()).isEqualTo(1);
        assertThat(first.path("amount").asLong()).isEqualTo(6000L); // 3000×2 服务端算
        assertThat(first.path("sourceRef").asText()).isEqualTo("ORD-IT-A");
        JsonNode second = page.path("content").get(1);
        assertThat(second.path("unitPriceSnapshot").asLong()).isEqualTo(2000L);
        assertThat(second.path("amount").asLong()).isEqualTo(2000L);
    }

    @Test
    @Order(3)
    @DisplayName("同帧重投幂等：BILL-1009 吞过，费用行数与金额零变化")
    void duplicateDeliveryIsIdempotent() throws InterruptedException {
        long before = countFees(VISIT_A);
        publishOrderEvent("ORD-IT-A", VISIT_A);
        Thread.sleep(2000); // 覆盖一次有界重试窗口（1s/2s/4s 退避首跳）
        assertThat(countFees(VISIT_A)).isEqualTo(before);
    }

    private long countFees(String visitId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND status <> 'CANCELLED'",
                Long.class,
                visitId);
        return n == null ? 0 : n;
    }

    @Test
    @Order(4)
    @DisplayName("预结算→正式结算：勾稽放行、费用批量 SETTLED、settlement.completed 事件可消费")
    void previewAndSettlePublishsCompleted() throws InterruptedException {
        // 两行 PENDING 合计 8000 分（3000×2 + 2000×1）
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", VISIT_A).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        String settleNo = pv.path("settleNo").asText();
        assertThat(pv.path("totalAmount").asLong()).isEqualTo(8000L);
        assertThat(pv.path("status").asText()).isEqualTo("DRAFT");

        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", settleNo);
        // 支付明细行（PaymentLine 冻结形态）：全自费现金一行，amount=该单应结总额
        //   （Σpayments.amount==totalAmount 第二层勾稽硬约束，不平 BILL-1016）；无 CARD_BALANCE 行=不扣卡
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "8000");
        JsonNode sv = postJson("/api/v1/billing/settlements", adminToken, settle);
        assertThat(sv.path("status").asText()).isEqualTo("SETTLED");

        // 数据库写操作核验：费用回挂结算单且批量 SETTLED
        Integer settledFees = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND status = 'SETTLED' AND settlement_id IS NOT NULL",
                Integer.class,
                VISIT_A);
        assertThat(settledFees).isEqualTo(2);

        // CF-4 事件面：结算完成经 fy.topic 可消费（M03 放行发药依据）
        assertThat(ItCaptureConfig.SETTLEMENT_LATCH.await(10, TimeUnit.SECONDS)).isTrue();
        EventEnvelope captured = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> "billing.settlement.completed".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(captured.payload().path("settleNo").asText()).isEqualTo(settleNo);
        assertThat(captured.payload().path("totalAmount").asText()).isEqualTo("8000");
        assertThat(captured.producer()).isEqualTo("billing");

        // 幂等重放：同单再结算直返既有结果不重复扣费
        JsonNode replay = postJson("/api/v1/billing/settlements", adminToken, settle);
        assertThat(replay.path("settleNo").asText()).isEqualTo(settleNo);
    }

    @Test
    @Order(5)
    @DisplayName("免审直退路径：阈值内当日全退 auto_approved=true、refund.approved 事件承载、两行 FULL_REFUND 结算转 REFUNDED")
    void autoExemptRefundPath() throws InterruptedException {
        List<Long> feeIds = jdbcTemplate.queryForList(
                "SELECT id FROM billing.fee_record WHERE visit_id = ? ORDER BY id", Long.class, VISIT_A);
        assertThat(feeIds).hasSize(2);
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT settlement_id FROM billing.fee_record WHERE id = ?", Long.class, feeIds.get(0));
        ObjectNode apply = objectMapper.createObjectNode();
        apply.put("settlementId", settlementId).put("reason", "IT 当日更正全退");
        ArrayNode lines = apply.putArray("lines");
        lines.addObject().put("feeId", feeIds.get(0)).put("refundQuantity", "2");
        lines.addObject().put("feeId", feeIds.get(1)).put("refundQuantity", "1");
        long refundId = postJson("/api/v1/billing/refunds", adminToken, apply).asLong();

        // 服务端聚合 8000 ≤ 免审阈值 50000、当日无执行占用 → apply 即 APPROVED + auto_approved 标识
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT auto_approved::text FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("true");

        // refund.approved 事件可消费且承载免审标识（V605 id 20 口径：同事件 autoApproved 区分）
        assertThat(ItCaptureConfig.REFUND_LATCH.await(10, TimeUnit.SECONDS)).isTrue();
        EventEnvelope refundEvent = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> "billing.refund.approved".equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(refundEvent.payload().path("autoApproved").asBoolean()).isTrue();

        postJson("/api/v1/billing/refunds/" + refundId + "/execute", adminToken, objectMapper.createObjectNode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.settlement WHERE id = ?", String.class, settlementId))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM billing.fee_record WHERE visit_id = ? AND status = 'FULL_REFUND'",
                        Integer.class,
                        VISIT_A))
                .isEqualTo(2);
    }

    @Test
    @Order(6)
    @DisplayName("双人审批路径：超阈值进 PENDING_APPROVAL，自审拒 BILL-1020，异账号批准后放行")
    void twoPersonApprovalPath() {
        // 审批路径构造：第二笔开单走事件（60000 分 > 免审阈值 50000）——先补高价项目
        String bigItem = ITEM_CODE + "-BIG";
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", bigItem)
                .put("itemName", "IT 大额检查")
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        // 实测收口：同 newItemWithPrice，取 VO 的 .id
        long bigItemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（同 newItemWithPrice 注记）
        draft.put("itemCode", bigItem)
                .put("price", 3000)
                .put(
                        "effectiveFrom",
                        LocalDate.now(ZoneOffset.UTC)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString())
                .put("priceSource", "OFFICIAL_DOC");
        long bigPriceId = postJson("/api/v1/billing/charge-items/" + bigItemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + bigPriceId + "/publish",
                adminToken,
                objectMapper.createObjectNode());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", "ORD-IT-B").put("patientId", PATIENT_ID).put("visitId", VISIT_B);
        payload.putArray("lines").addObject().put("itemCode", bigItem).put("quantity", 20);
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "outpatient.order.created",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "outpatient",
                        "outpatient.order.created",
                        "it-flow-B",
                        objectMapper.convertValue(payload, Map.class)));

        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", VISIT_B).put("payerType", "SELF_PAY");
        // 轮询费用到位后结算（复用 awaitFees 语义，期望 1 行 60000 分）
        try {
            awaitFees(VISIT_B, 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong()).isEqualTo(60000L);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", pv.path("settleNo").asText());
        // 支付明细行：全自费现金=该单总额 60000（Σpayments.amount==totalAmount 勾稽；无 CARD_BALANCE 行不扣卡）
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "60000");
        postJson("/api/v1/billing/settlements", adminToken, settle);

        Long feeId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.fee_record WHERE visit_id = ? LIMIT 1", Long.class, VISIT_B);
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT settlement_id FROM billing.fee_record WHERE id = ?", Long.class, feeId);
        long refundId = postJson("/api/v1/billing/refunds", adminToken, refundBody(settlementId, feeId, "20"))
                .asLong();
        String preStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId);
        assertThat(preStatus).isEqualTo("PENDING_APPROVAL"); // 60000 > autoExemptFen 50000 进审批

        // 自审守卫：申请人=审批人（admin）拒 403 BILL-1020
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        org.springframework.http.ResponseEntity<String> selfApprove = restTemplate.postForEntity(
                "/api/v1/billing/refunds/" + refundId + "/approve", new HttpEntity<>(headers), String.class);
        assertThat(selfApprove.getStatusCode().value()).isEqualTo(403);

        postJson("/api/v1/billing/refunds/" + refundId + "/approve", reviewerToken, objectMapper.createObjectNode());
        String approved = jdbcTemplate.queryForObject(
                "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId);
        assertThat(approved).isEqualTo("APPROVED");
        postJson("/api/v1/billing/refunds/" + refundId + "/execute", reviewerToken, objectMapper.createObjectNode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("EXECUTED");
    }

    @Test
    @Order(7)
    @DisplayName("二级审批路径：超一级上限升 PENDING_SECOND_APPROVAL、一级审批人连批拒 403、二级终批后事件承载")
    void secondLevelApprovalPath() throws InterruptedException {
        // 第三账号播种（二级终批人：与申请人 admin、一级审批人 it-reviewer 三方互异，连批守卫前提）
        seedReviewer2User();
        String reviewer2Token = loginToken(REVIEWER2_LOGIN_NAME);

        // 大额造数：单行 3000 分 × 70 = 210000 分 > singleApprovalFen 200000 → L2 二级审批
        newItemWithPrice(LARGE_ITEM_CODE, 3000);
        publishOrderEvent("ORD-IT-C", VISIT_C, LARGE_ITEM_CODE, 70);
        awaitFees(VISIT_C, 1);

        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", VISIT_C).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong()).isEqualTo(210000L);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", pv.path("settleNo").asText());
        settle.putArray("payments").addObject().put("method", "CASH").put("amount", "210000");
        postJson("/api/v1/billing/settlements", adminToken, settle);

        Long feeId = jdbcTemplate.queryForObject(
                "SELECT id FROM billing.fee_record WHERE visit_id = ? LIMIT 1", Long.class, VISIT_C);
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT settlement_id FROM billing.fee_record WHERE id = ?", Long.class, feeId);
        long refundId = postJson("/api/v1/billing/refunds", adminToken, refundBody(settlementId, feeId, "70"))
                .asLong();
        String refundIdText = String.valueOf(refundId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("PENDING_APPROVAL"); // L2 亦从待一审起（级别在批时判定）

        // 一级审批（收费组长侧）：超上限单升待二级 + 一级审批链落库；终批人空、事件不发（时点=终批）
        // 注：OperatorContextHolder 取登录会话 userId（AuthTokenInterceptor:76），故留痕断言取用户 id 文本
        postJson("/api/v1/billing/refunds/" + refundId + "/approve", reviewerToken, objectMapper.createObjectNode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT first_approver FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo(String.valueOf(REVIEWER_USER_ID));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT first_approved_at IS NOT NULL FROM billing.refund_request WHERE id = ?",
                        Boolean.class,
                        refundId))
                .isTrue();
        assertThat(ItCaptureConfig.CAPTURED.stream()
                        .filter(e -> "billing.refund.approved".equals(e.eventType()))
                        .noneMatch(e ->
                                refundIdText.equals(e.payload().path("refundId").asText())))
                .as("一级批不发事件（billing.refund.approved 事件时点=终批）")
                .isTrue();

        // 连批守卫：一级审批人同账号再批二级 → 403（同一账号不得连批两级，BILL-1020 语义扩展）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(reviewerToken);
        org.springframework.http.ResponseEntity<String> repeatApprove = restTemplate.postForEntity(
                "/api/v1/billing/refunds/" + refundId + "/approve", new HttpEntity<>(headers), String.class);
        assertThat(repeatApprove.getStatusCode().value()).isEqualTo(403);

        // 二级终批（财务/医保办侧）：APPROVED + 终批人留痕 + refund.approved 事件到达
        postJson("/api/v1/billing/refunds/" + refundId + "/approve", reviewer2Token, objectMapper.createObjectNode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT approver FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo(String.valueOf(REVIEWER2_USER_ID));
        EventEnvelope approved = awaitRefundApprovedEvent(refundIdText);
        assertThat(approved.payload().path("autoApproved").asBoolean()).isFalse();

        postJson("/api/v1/billing/refunds/" + refundId + "/execute", reviewer2Token, objectMapper.createObjectNode());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.refund_request WHERE id = ?", String.class, refundId))
                .isEqualTo("EXECUTED");
    }

    private ObjectNode refundBody(long settlementId, long feeId, String quantity) {
        ObjectNode apply = objectMapper.createObjectNode();
        apply.put("settlementId", settlementId).put("reason", "IT 退费验证");
        apply.putArray("lines").addObject().put("feeId", feeId).put("refundQuantity", quantity);
        return apply;
    }
}

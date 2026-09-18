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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * 价格快照不漂移验收锚点 IT（PLAN-P1-01 §PR-3 验收行②，M13 方案 3.4）：v1 计费→调价生效广播→
 * v2 新费用按新价→**历史费用行单价/版本/目录列零漂移**、旧结算取价仍按快照。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingPriceSnapshotIT extends FuyunStackITBase {

    /** 类级独占三容器（第 2 轮审查 P1-1 裁决：容器禁收敛入基类，本 IT 独占一套 broker/DB/Redis，
     *  与结算 IT、Empi IT 物理隔离；EmpiGovernanceIT :74-90 同款形态）。
     *  @DynamicPropertySource 密钥已由 FuyunStackITBase 承载，本类不重复声明。 */
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

    private static final String ITEM_CODE = "C-IT-P-" + (System.nanoTime() % 1_000_000L);
    private static final String VISIT_OLD =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00011";
    private static final String VISIT_NEW =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00012";
    private static final long PATIENT_ID = 700102L;

    private static String adminToken = "";
    private static long itemId;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /**
     * 捕获调价广播（CF-4 price.published 可消费 + 事件先于新计费顺序证明）。A.5-4 治理红线：照
     * EmpiGovernanceIT ItQueueConfiguration 先例经 MessagingGovernance 声明（@Bean 返回 Declarables
     * 交 RabbitAdmin 幂等声明，禁自声明 DirectExchange/裸 Queue/Binding）——队列
     * q.it.billing.charge-item-price.published（q.<消费模块>.<事件三段名> 与构件命名同源）。声明
     * 副作用把 "it" 追加进 V605 id 22 事件订阅清单（事件已登记非 broadcast，构件不抛——实证
     * QueueGovernorImpl/EventRegistryServiceImpl，2026-09-17），与 Empi 先例同款 IT 环境可接受。
     */
    @TestConfiguration
    static class ItPublishCapture {

        static final String Q_NAME =
                MessagingConstants.QUEUE_PREFIX + "it." + BillingMessagingConstants.EVENT_PRICE_PUBLISHED;
        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itPricePublishQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", BillingMessagingConstants.EVENT_PRICE_PUBLISHED));
        }

        @Bean
        ItPriceListener itPriceListener(EventEnvelopeCodec codec) {
            return new ItPriceListener(codec);
        }
    }

    /** 监听器本体（与结算 IT 捕获器同款轻量形态）。 */
    static class ItPriceListener {

        private final EventEnvelopeCodec codec;

        ItPriceListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = ItPublishCapture.Q_NAME)
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            ItPublishCapture.CAPTURED.add(envelope);
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

    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return toNode(restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody());
    }

    private JsonNode toNode(String body) {
        // 实测收口：POST /price-adjustments/{id}/publish 为冻结契约 204 无体端点（Task 12 定稿），
        // 响应体 null 直入 readTree 抛「argument content is null」中断用例——无体响应回 MISSING 单例，
        // 仅对 204 端点忽略返回值；带体断言路径行为零变化
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 注入单行开单事件（指定就诊号）。 */
    private void publishSingleLine(String orderId, String visitId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId).put("patientId", PATIENT_ID).put("visitId", visitId);
        payload.putArray("lines").addObject().put("itemCode", ITEM_CODE).put("quantity", 1);
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "outpatient.order.created",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "outpatient",
                        "outpatient.order.created",
                        "it-snap-" + orderId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    private JsonNode awaitFees(String visitId, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode page = getJson("/api/v1/billing/fees?visitId=" + visitId, adminToken);
            if (page.path("content").size() >= expected) {
                return page;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("费用生成超时：" + visitId);
    }

    @Test
    @Order(1)
    @DisplayName("前置：v1 定价 3000 发布，开单事件生成费用冻结 v1 快照")
    void chargeUnderV1FreezesSnapshot() throws InterruptedException {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", ITEM_CODE)
                .put("itemName", "IT 快照锚点项目")
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        // 实测收口：POST /charge-items 直出 ChargeItemVO（非裸 id，Task 12 冻结形态）——取 .id 键后续链
        itemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（saveDraft 按码取项目，缺字段整请求 400）
        draft.put("itemCode", ITEM_CODE)
                .put("price", 3000)
                .put(
                        "effectiveFrom",
                        LocalDate.now(ZoneOffset.UTC)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString())
                .put("priceSource", "OFFICIAL_DOC");
        long v1 = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson("/api/v1/billing/price-adjustments/" + v1 + "/publish", adminToken, objectMapper.createObjectNode());

        publishSingleLine("ORD-SNAP-1", VISIT_OLD);
        JsonNode fee = awaitFees(VISIT_OLD, 1).path("content").get(0);
        assertThat(fee.path("unitPriceSnapshot").asLong()).isEqualTo(3000L);
        assertThat(fee.path("priceVersion").asInt()).isEqualTo(1);
        assertThat(fee.path("amount").asLong()).isEqualTo(3000L);
    }

    @Test
    @Order(2)
    @DisplayName("调价 v2=4000 生效：闭旧区间、置新版、published 广播可消费")
    void publishV2Broadcasts() throws InterruptedException {
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（同 Order(1) 注记）
        draft.put("itemCode", ITEM_CODE)
                .put("price", 4000)
                .put(
                        "effectiveFrom",
                        LocalDate.now(ZoneOffset.UTC)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                                .toString())
                .put("priceSource", "OFFICIAL_DOC");
        long v2 = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson("/api/v1/billing/price-adjustments/" + v2 + "/publish", adminToken, objectMapper.createObjectNode());

        // 实测收口：捕获监听器上下文启动即在位，Order(1) 的 v1 广播帧同样入列——单闩 await 退化为
        // 恒真、get(0) 取到 v1 帧；改为轮询捕获清单等 priceVersion=2 帧再断言（CF-4 可消费实证语义不变、时序鲁棒）
        EventEnvelope captured = null;
        for (int i = 0; i < 100 && captured == null; i++) {
            captured = ItPublishCapture.CAPTURED.stream()
                    .filter(e -> e.payload().path("priceVersion").asInt() == 2)
                    .findFirst()
                    .orElse(null);
            if (captured == null) {
                Thread.sleep(100);
            }
        }
        assertThat(captured).as("q.it 捕获队列应在 10s 内收到 priceVersion=2 的调价广播帧").isNotNull();
        assertThat(captured.payload().path("price").asText()).isEqualTo("4000");

        // 版本区间收口：v1 EXPIRED + effective_to 回填，当前 PUBLISHED 唯一
        Integer expired = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.charge_item_price WHERE charge_item_id = ? AND status = 'EXPIRED' AND effective_to IS NOT NULL",
                Integer.class,
                itemId);
        assertThat(expired).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("v2 新费用按新价、历史费用行快照与金额零漂移（调价不溯既往）")
    void historyFeeNeverDrifts() throws InterruptedException {
        JsonNode before = awaitFees(VISIT_OLD, 1).path("content").get(0);
        publishSingleLine("ORD-SNAP-2", VISIT_NEW);
        JsonNode newFee = awaitFees(VISIT_NEW, 1).path("content").get(0);
        assertThat(newFee.path("unitPriceSnapshot").asLong()).isEqualTo(4000L);
        assertThat(newFee.path("priceVersion").asInt()).isEqualTo(2);

        JsonNode oldFee = getJson("/api/v1/billing/fees?visitId=" + VISIT_OLD, adminToken)
                .path("content")
                .get(0);
        assertThat(oldFee.path("unitPriceSnapshot").asLong()).isEqualTo(3000L); // 零漂移
        assertThat(oldFee.path("priceVersion").asInt()).isEqualTo(1);
        assertThat(oldFee.path("amount").asLong())
                .isEqualTo(before.path("amount").asLong());

        // 结算取价按快照：旧就诊预结算总额=3000 而非现价 4000
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", VISIT_OLD).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        assertThat(pv.path("totalAmount").asLong()).isEqualTo(3000L);
    }
}

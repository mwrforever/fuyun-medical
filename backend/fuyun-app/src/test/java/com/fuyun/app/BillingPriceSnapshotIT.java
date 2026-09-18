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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 *
 * <p>Order(4) 覆盖「定时生效」路径（FU-M13-01 / Spec §10 边界「调价生效瞬间的前后两笔费用」）：
 * 未来起点版本发布后到点前取旧价、旧行区间未提前闭；到点（区间边界抵达，确定性模拟不真实等待）
 * 后取新价且历史快照零漂移。
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
    /** 未来起点调价项目（Order(4) 独立项目：版本链不与 ITEM_CODE 项目混用） */
    private static final String ITEM_CODE_FUTURE = "C-IT-PF-" + (System.nanoTime() % 1_000_000L);

    private static final String VISIT_OLD =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00011";
    private static final String VISIT_NEW =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00012";
    /** 未来起点项目：到点前取价就诊号（Order(4)） */
    private static final String VISIT_BEFORE_DUE =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00013";
    /** 未来起点项目：到点后取价就诊号（Order(4)） */
    private static final String VISIT_AFTER_DUE =
            "O" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE) + "00014";

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

    /** 注入单行开单事件（指定就诊号，项目固定为 ITEM_CODE 项目的价格链）。 */
    private void publishSingleLine(String orderId, String visitId) {
        publishSingleLine(orderId, visitId, ITEM_CODE);
    }

    /** 注入单行开单事件（指定就诊号 + 项目编码：Order(4) 未来起点项目复用同一计费链路）。 */
    private void publishSingleLine(String orderId, String visitId, String itemCode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("orderId", orderId).put("patientId", PATIENT_ID).put("visitId", visitId);
        payload.putArray("lines").addObject().put("itemCode", itemCode).put("quantity", 1);
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

    /**
     * 新建调价草稿并发布（返回草稿行 id）：Order(4) 未来起点版本造数入口。
     *
     * @param targetItemId  收费项目 id（草稿归属项目，POST 路径变量）
     * @param price         单价（分）
     * @param effectiveFrom 生效起点（区间左闭端点；必须 UTC 瞬时构造，见 Order(4) 时区收口注记）
     * @return 草稿行 id
     */
    private long publishDraft(long targetItemId, long price, OffsetDateTime effectiveFrom) {
        ObjectNode draft = objectMapper.createObjectNode();
        // PriceDraftRequest.itemCode @NotBlank 必填（saveDraft 按码取项目，缺字段整请求 400）
        draft.put("itemCode", ITEM_CODE_FUTURE)
                .put("price", price)
                .put("effectiveFrom", effectiveFrom.toString())
                .put("priceSource", "OFFICIAL_DOC");
        long draftId = postJson("/api/v1/billing/charge-items/" + targetItemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + draftId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
        return draftId;
    }

    /**
     * 等待指定项目 + 版本号的调价广播帧（CF-4 可消费；轮询捕获清单，10s 超时返回 null）。
     *
     * @param itemCode     项目编码（载荷字段过滤，区分同一 IT 内多项目的广播）
     * @param priceVersion 价格版本号
     * @return 命中的事件信封；超时未捕获返回 null
     */
    private EventEnvelope awaitPriceFrame(String itemCode, int priceVersion) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            EventEnvelope hit = ItPublishCapture.CAPTURED.stream()
                    .filter(e -> itemCode.equals(e.payload().path("itemCode").asText()))
                    .filter(e -> e.payload().path("priceVersion").asInt() == priceVersion)
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                return hit;
            }
            Thread.sleep(100);
        }
        return null;
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
        // 生效起点＝当前瞬时前 10 分钟（区间判定修订后必须落在过去）：相对瞬时构造，不用 UTC 日界
        // atStartOfDay——UTC 零点在本地 08:00（Asia/Shanghai），凌晨运行时日界值晚于当前时刻，
        // 会被区间判定当成未来版本（v1 不生效）而误判 BILL-1008
        draft.put("itemCode", ITEM_CODE)
                .put("price", 3000)
                .put(
                        "effectiveFrom",
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).toString())
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
        // PriceDraftRequest.itemCode @NotBlank 必填（同 Order(1) 注记）；生效起点前 5 分钟（晚于 v1 起点
        // 且落在过去——倒挂守卫与区间判定双向满足：闭旧后 v1 区间闭合、v2 覆盖当前时刻）
        draft.put("itemCode", ITEM_CODE)
                .put("price", 4000)
                .put(
                        "effectiveFrom",
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString())
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

    @Test
    @Order(4)
    @DisplayName("未来起点调价不提前生效：到点前取旧价、旧行区间未提前闭；到点后取新价且历史快照零漂移")
    void futureDatedPublishTakesEffectAtEffectiveFrom() throws InterruptedException {
        // 独立项目承载未来起点版本（版本号项目内自增，不与 Order(1)-(3) 项目版本链混用）
        ObjectNode futureItem = objectMapper.createObjectNode();
        futureItem
                .put("itemCode", ITEM_CODE_FUTURE)
                .put("itemName", "IT 未来起点调价项目")
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "EXAM_FEE");
        long futureItemId = postJson("/api/v1/billing/charge-items", adminToken, futureItem)
                .path("id")
                .asLong();
        // 造数时区收口（本次区间判定修订配套）：区间端点取「当前瞬时 ± 偏移」，不用 UTC 日界 atStartOfDay
        //   （本地 Asia/Shanghai 凌晨运行时 UTC 日界晚于当前时刻，会把已生效版本误造为未来版本）
        OffsetDateTime activeFrom =
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime dueFrom = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.SECONDS);
        long v1 = publishDraft(futureItemId, 5000L, activeFrom);
        long v2 = publishDraft(futureItemId, 6000L, dueFrom);

        // 区间落库：旧行闭到未来起点（到点前仍覆盖当前时刻）且 EXPIRED；新行未闭等到点（无提前生效）
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.charge_item_price WHERE id = ?", String.class, v1))
                .isEqualTo("EXPIRED");
        OffsetDateTime closedAt = jdbcTemplate.queryForObject(
                "SELECT effective_to FROM billing.charge_item_price WHERE id = ?", OffsetDateTime.class, v1);
        assertThat(closedAt.toInstant()).isEqualTo(dueFrom.toInstant());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM billing.charge_item_price WHERE id = ?", String.class, v2))
                .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT effective_to IS NULL FROM billing.charge_item_price WHERE id = ?", Boolean.class, v2))
                .isTrue();
        // 广播契约：发布时点即广播、载荷携生效时刻 effectiveFrom（工作站据此到点刷新缓存）
        EventEnvelope frame = awaitPriceFrame(ITEM_CODE_FUTURE, 2);
        assertThat(frame).as("应捕获未来起点版本的调价广播帧").isNotNull();
        assertThat(Instant.parse(frame.payload().path("effectiveFrom").asText()))
                .isEqualTo(dueFrom.toInstant());

        // 到点前取价：新费用行冻结旧价（未来起点版本不得提前生效）
        publishSingleLine("ORD-SNAP-F1", VISIT_BEFORE_DUE, ITEM_CODE_FUTURE);
        JsonNode beforeDue = awaitFees(VISIT_BEFORE_DUE, 1).path("content").get(0);
        assertThat(beforeDue.path("unitPriceSnapshot").asLong()).isEqualTo(5000L);
        assertThat(beforeDue.path("priceVersion").asInt()).isEqualTo(1);
        assertThat(beforeDue.path("amount").asLong()).isEqualTo(5000L);

        // 到点切换（确定性模拟，禁真实等待）：把区间边界回移到过去——等价于「生效时刻已抵达」的库状态
        //   （旧行区间在边界闭合、新行区间自边界起覆盖当前时刻），无任何调度/激活动作，取价侧自然切换
        Timestamp arrived = Timestamp.from(Instant.now().minusSeconds(1));
        jdbcTemplate.update("UPDATE billing.charge_item_price SET effective_to = ? WHERE id = ?", arrived, v1);
        jdbcTemplate.update("UPDATE billing.charge_item_price SET effective_from = ? WHERE id = ?", arrived, v2);

        // 到点后取价：新费用行取新价；到点前生成的费用行快照零漂移（调价不溯既往）
        publishSingleLine("ORD-SNAP-F2", VISIT_AFTER_DUE, ITEM_CODE_FUTURE);
        JsonNode afterDue = awaitFees(VISIT_AFTER_DUE, 1).path("content").get(0);
        assertThat(afterDue.path("unitPriceSnapshot").asLong()).isEqualTo(6000L);
        assertThat(afterDue.path("priceVersion").asInt()).isEqualTo(2);
        JsonNode historyFee = getJson("/api/v1/billing/fees?visitId=" + VISIT_BEFORE_DUE, adminToken)
                .path("content")
                .get(0);
        assertThat(historyFee.path("unitPriceSnapshot").asLong()).isEqualTo(5000L);
        assertThat(historyFee.path("priceVersion").asInt()).isEqualTo(1);
    }
}

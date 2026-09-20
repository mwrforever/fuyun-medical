package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 死信治理端到端 IT（PR-1 验收明文「死信→重推→消费成功闭环」）：真实三中间件打通
 * 「消费失败 → FAILED 留痕 → 有界重试耗尽 → fy.dlx → dead_letter(PENDING) → 重放 → 消费成功
 * → 台账行内升级 PROCESSED → dead_letter(REPLAYED)」全链，并覆盖重推上限、关闭留痕与主数据
 * 分发流水（FU-M20-04「本模块记分发流水」）。
 *
 * <p>容器三件套与 MessagingGovernanceIT 完全同款（tag 与 deploy compose 严格一致 +
 * it/rabbitmq.conf 挂载 + static 类级共享 + @ServiceConnection）；测试 profile 的重试为
 * 100ms/1.0（3 次亚秒级耗尽），死信链路可在 15s 等待窗内收敛。不引入 awaitility。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeadLetterGovernanceIT {

    /** TimescaleDB 容器：dead_letter / received_event / mdm_* 断言目标库 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：幂等前置键真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：消费重试耗尽 → fy.dlx → 死信落库 → 重放回 fy.topic 的真实链路 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（仅具 IT 意义，与任何真实凭证无关） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 测试消费者域标识：幂等键第二要素与队列命名第一段 */
    private static final String CONSUMER_MODULE = "it";

    /** 闭环链路事件类型：CF-2 首批种子事件（V5 登记行） */
    private static final String EVENT_TYPE = "system.dict.published";

    /** 监听队列名：构件命名规则 q.<consumerModule>.<eventType> */
    private static final String QUEUE_NAME = MessagingConstants.QUEUE_PREFIX + CONSUMER_MODULE + "." + EVENT_TYPE;

    /** 链路等待上限：覆盖消费重试（100ms×3）+ 死信转发 + 死信消费落库 + 重放回投 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(20);

    /** 轮询间隔：200ms 步进（仓库既有口径） */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /**
     * 测试消费者业务开关：true=业务失败（模拟毒丸），false=业务成功（重放后恢复正常）。
     *
     * <p>用例内时序（@Order 串联、跨用例共享静态状态，改动任一用例前必须复核本时序）：
     * ①初值 true——@Order(1) 制造首帧死信；②@Order(2) 起始置 false——重放后业务成功；
     * ③@Order(3) 起始置 **true** 制造第二帧死信（关闭场景），结尾复位 false；④@Order(4) 保持 false。
     */
    static final AtomicBoolean FAIL_MODE = new AtomicBoolean(true);

    /** 业务成功计数：重放后恰好 +1（幂等拦截证明——同 eventId 不重复进入业务） */
    static final AtomicInteger BUSINESS_COUNT = new AtomicInteger();

    /** 测试消费者：标准幂等范式（tryAcquire → 业务 → recordProcessed；失败 settleFailure + 重抛） */
    static class DeadLetterProbeConsumer {

        private final MessageIdempotencyService idempotencyService;

        private final EventEnvelopeCodec codec;

        DeadLetterProbeConsumer(MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            this.idempotencyService = idempotencyService;
            this.codec = codec;
        }

        @RabbitListener(queues = QUEUE_NAME)
        void onDictPublished(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (!idempotencyService.tryAcquire(envelope.eventId(), CONSUMER_MODULE)) {
                return;
            }
            ReceivedEventRecord record = new ReceivedEventRecord(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer(),
                    envelope.occurredAt(),
                    CONSUMER_MODULE);
            try {
                if (FAIL_MODE.get()) {
                    throw new IllegalStateException("模拟业务失败：毒丸帧触发有界重试与死信链路");
                }
                BUSINESS_COUNT.incrementAndGet();
                idempotencyService.recordProcessed(record);
            } catch (RuntimeException e) {
                idempotencyService.settleFailure(record, e);
                throw e;
            }
        }
    }

    /** 测试装配：消费队列经治理构件声明（禁测试私建队列，M20 红线）；消费者 Bean 注册 */
    @TestConfiguration
    static class DeadLetterProbeConfig {

        @Bean
        Declarables itDeadLetterProbeQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(new ConsumerQueueSpec(CONSUMER_MODULE, EVENT_TYPE));
        }

        @Bean
        DeadLetterProbeConsumer deadLetterProbeConsumer(
                MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            return new DeadLetterProbeConsumer(idempotencyService, codec);
        }
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    EventEnvelopeCodec codec;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IDeadLetterService deadLetterService;

    @Autowired
    IMdmSubscriptionService mdmSubscriptionService;

    /** 场景一产出的死信 eventId：场景二重放复用 */
    private static String firstDeadLetterEventId;

    @Test
    @Order(1)
    @DisplayName("消费失败留痕：poison 帧重试耗尽落死信 PENDING，received_event 留 FAILED 行并累加失败次数")
    void poisonFrameLandsAsPendingDeadLetterAndFailedConsumption() {
        String eventId = publishPoisonFrame();

        Map<String, Object> deadLetter = awaitDeadLetterRow(eventId);
        assertThat(deadLetter.get("status")).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
        assertThat(deadLetter.get("source_queue")).isEqualTo(QUEUE_NAME);
        assertThat(deadLetter.get("replay_count")).isEqualTo(0);

        // Spec §3.2 步骤⑤：失败记录原因；D-7 口径：FAILED 行不得被回查误判为已处理
        awaitUntil(
                () -> !jdbcTemplate
                        .queryForList(
                                "SELECT retry_count FROM integration.received_event"
                                        + " WHERE event_id = CAST(? AS uuid) AND consumer_module = ? AND status = 'FAILED'",
                                eventId,
                                CONSUMER_MODULE)
                        .isEmpty(),
                "消费失败未落 FAILED 台账行");
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM integration.received_event WHERE event_id = CAST(? AS uuid) AND consumer_module = ?",
                Integer.class,
                eventId,
                CONSUMER_MODULE);
        assertThat(retryCount).as("容器有界重试 3 次应累计失败登记次数").isGreaterThanOrEqualTo(1);

        firstDeadLetterEventId = eventId;
    }

    @Test
    @Order(2)
    @DisplayName("重放闭环：重放后消费成功且业务仅执行一次，FAILED 行升级 PROCESSED，死信置 REPLAYED 并计数")
    void replayClosesTheLoopAndUpgradesFailedRow() {
        FAIL_MODE.set(false);
        Long deadLetterId = deadLetterIdByEventId(firstDeadLetterEventId);
        int businessBefore = BUSINESS_COUNT.get();

        DeadLetterDetailVO replayed = deadLetterService.replay(deadLetterId);

        assertThat(replayed.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.handler()).isNotNull();
        awaitUntil(() -> BUSINESS_COUNT.get() == businessBefore + 1, "重放帧未被消费者业务处理（或重复进入业务）");
        awaitUntil(
                () -> {
                    String status = jdbcTemplate.queryForObject(
                            "SELECT status FROM integration.received_event"
                                    + " WHERE event_id = CAST(? AS uuid) AND consumer_module = ?",
                            String.class,
                            firstDeadLetterEventId,
                            CONSUMER_MODULE);
                    return MessagingConstants.RECEIVED_STATUS_PROCESSED.equals(status);
                },
                "重放成功后消费台账行未升级为已处理（FAILED → PROCESSED）");
    }

    @Test
    @Order(3)
    @DisplayName("上限与终态守卫：replay_count 达 3 拒绝重放（INT-1003）；关闭留痕后终态禁重放（INT-1002）")
    void replayLimitAndClosedStateAreEnforced() {
        // 上限守卫：构造已达上限的 PENDING 死信（控制器的重推上限口径 = 3 次）
        Long replayedId = deadLetterIdByEventId(firstDeadLetterEventId);
        jdbcTemplate.update(
                "UPDATE integration.dead_letter SET status = 'PENDING', replay_count = ? WHERE id = ?",
                MessagingConstants.DEAD_LETTER_REPLAY_MAX_COUNT,
                replayedId);
        assertThatThrownBy(() -> deadLetterService.replay(replayedId))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        // 关闭留痕：先把业务开关拨回失败态（@Order(2) 结尾已置 false；不复位则第二帧业务成功、
        // 死信永不落库 → 下方 await 必超时），再另起一帧 poison 并经关闭处置留痕
        FAIL_MODE.set(true);
        String secondEventId = publishPoisonFrame();
        awaitDeadLetterRow(secondEventId);
        Long secondId = deadLetterIdByEventId(secondEventId);
        DeadLetterDetailVO closed = deadLetterService.close(secondId, new DeadLetterCloseRequest("脏数据放弃：测试场景关闭"));
        assertThat(closed.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        assertThat(closed.handleNote()).isEqualTo("脏数据放弃：测试场景关闭");
        assertThat(closed.handler()).isNotNull();
        assertThat(closed.handledAt()).isNotNull();

        // 终态守卫：CLOSED 不得再重放（Spec §5）
        assertThatThrownBy(() -> deadLetterService.replay(secondId)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        FAIL_MODE.set(false);
    }

    @Test
    @Order(4)
    @DisplayName("主数据分发流水：订阅登记后广播事件落 mdm_dispatch_log，target_modules 含订阅方")
    void masterDataBroadcastRecordsDispatchLog() {
        mdmSubscriptionService.register(new MdmSubscriptionCreateRequest(
                MdmConstants.TOPIC_DICT, CONSUMER_MODULE, MdmConstants.SYNC_MODE_EVENT_SUBSCRIBE));
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(),
                "system",
                EVENT_TYPE,
                "it-dead-letter-governance",
                Map.of("dictType", "gender", "version", 7));

        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, envelope);

        awaitUntil(
                () -> !jdbcTemplate
                        .queryForList(
                                "SELECT id FROM integration.mdm_dispatch_log WHERE topic = ? AND version = ?",
                                MdmConstants.TOPIC_DICT,
                                7)
                        .isEmpty(),
                "主数据广播事件未落分发流水");
        Map<String, Object> row = jdbcTemplate
                .queryForList(
                        "SELECT dispatch_mode, target_modules FROM integration.mdm_dispatch_log WHERE topic = ? AND version = ?",
                        MdmConstants.TOPIC_DICT,
                        7)
                .get(0);
        assertThat(row.get("dispatch_mode")).isEqualTo(MdmConstants.DISPATCH_MODE_BROADCAST);
        assertThat((String) row.get("target_modules")).contains(CONSUMER_MODULE);

        // 矩阵查询（FU-M20-04 管理面）：登记行经服务读出且对账状态为待对账初值
        assertThat(mdmSubscriptionService
                        .query(new MdmSubscriptionQuery(MdmConstants.TOPIC_DICT, CONSUMER_MODULE, 0, 20))
                        .content())
                .hasSize(1)
                .first()
                .extracting(vo -> vo.reconStatus())
                .isEqualTo(MdmConstants.RECON_STATUS_PENDING);
    }

    /**
     * 发布一帧毒丸信封（payload 含 poison 标记，业务开关 FAIL_MODE 决定是否失败）。
     *
     * @return 该帧信封 eventId
     */
    private String publishPoisonFrame() {
        EventEnvelope envelope = codec.create(
                Clock.systemUTC(), "system", EVENT_TYPE, "it-dead-letter-governance", Map.of("poison", true));
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, envelope);
        return envelope.eventId();
    }

    /**
     * 轮询等待死信落库并返回该行视图。
     *
     * <p>按来源队列收敛到 it 测试消费者自身行：PR-4 起 pharmacy 亦订阅 system.dict.published（V607
     * 字典水位消费），同一毒丸 eventId 会在 it 与 pharmacy 两队列各自死信落行（W-9 口径：dead_letter
     * 无唯一约束，同 eventId 可因不同消费者多次死信），不按 source_queue 过滤将命中他消费者行。
     *
     * @param eventId 信封 eventId，非空
     * @return 死信行字段视图（id/source_queue/status/replay_count）
     */
    private Map<String, Object> awaitDeadLetterRow(String eventId) {
        awaitUntil(
                () -> !jdbcTemplate
                        .queryForList(
                                "SELECT id FROM integration.dead_letter WHERE event_id = ? AND source_queue = ?",
                                eventId,
                                QUEUE_NAME)
                        .isEmpty(),
                "死信未在等待窗内落库：event_id=" + eventId);
        return jdbcTemplate
                .queryForList(
                        "SELECT id, source_queue, status, replay_count FROM integration.dead_letter"
                                + " WHERE event_id = ? AND source_queue = ?",
                        eventId,
                        QUEUE_NAME)
                .get(0);
    }

    /**
     * 按 eventId 取死信主键。
     *
     * <p>限定 it 测试消费者来源队列（依据同 {@link #awaitDeadLetterRow}）：同一 eventId 存在多消费者
     * 各自死信行时，无队列过滤的单行断言会因实际行数大于一而失真。
     *
     * @param eventId 信封 eventId，非空
     * @return 死信主键（雪花 ID）
     */
    private Long deadLetterIdByEventId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM integration.dead_letter WHERE event_id = ? AND source_queue = ?",
                Long.class,
                eventId,
                QUEUE_NAME);
    }

    /**
     * 通用轮询等待（200ms 步进，LINK_TIMEOUT 超时）：不引入 awaitility 的仓库既有口径。
     *
     * @param condition 等待条件，非空
     * @param message   超时失败信息（业务语义），非空
     */
    private void awaitUntil(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail(message + "（" + LINK_TIMEOUT + " 超时）");
    }
}

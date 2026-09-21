package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.system.api.DictPublishedPayload;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
 * 消息治理端到端集成测试（B2.3 交付）：以真实三中间件打通「发布 → 消费 → 幂等拦截 → 死信落库」闭环。
 *
 * <p>业务意图：验证 CF-1 信封契约与 M20 治理构件在真实链路下的协同——V5 种子登记行齐全（DoD 第 4 条
 * 迁移断言落点）、信封经 fy.topic 路由被治理构件声明的队列消费、同 eventId 重复投递被两层幂等拦截
 * （Redis NX 前置 + received_event 唯一索引，同时复验 B2.2 申报①的 UUID 真库写入链路）、poison 帧经
 * 有界重试耗尽进 fy.dlx 死信落库（PENDING 留痕）、治理构件副作用（订阅自动登记 + 死信统一队列声明）。
 *
 * <p>容器三件套与 {@link SmokeStackIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection），本类独立声明容器不改 SmokeStackIT。不含 fy.delay
 * TTL 到期时延断言（TASK.md T-R3-4 为压测待调研项，PR-2 只落声明构件）；不引入 awaitility（轮询
 * + 闩锁超时已覆盖等待语义）。
 *
 * <p>消费承接形态：简报 §4.4"参数 String 承接"在 spring-amqp 3.2 实证下不可直连（Jackson 转换器
 * 对 String 目标无法还原 JSON 对象原文），落地为 raw {@code Message} 承接原文 + UTF-8 解码为
 * String 后经 codec 解析——"原文进 codec、__TypeId__ 不作消费依据"的 CF-1 语义不变。
 *
 * <p>五步断言按序执行（@Order 串联断言链，消费状态跨步累积属业务链路语义）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessagingGovernanceIT {

    /** TimescaleDB 容器：event_registry / received_event / dead_letter 断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：幂等 Redis 前置键的真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：挂载与 compose rabbitmq.conf 同语义的服务端默认队列类型配置（默认类型 quorum） */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 测试资产假密钥（64 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /**
     * 注入测试用 HMAC 密钥：B3.2 认证链路装配后上下文含 SecurityProperties（fuyun.security.*），
     * 密钥缺失即启动 fail-fast；既有 IT 以假密钥维持消息治理链路语义（BRIEF-PR3-01 §5 同款姿态）。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 测试消费者域标识：幂等键第二要素、队列命名第一段，并经订阅自动登记写入 subscriber_modules */
    private static final String CONSUMER_MODULE = "it";

    /** 订阅/发布的事件类型：CF-2 首批种子事件（V5 登记行，M01 Spec §7 明示） */
    private static final String EVENT_TYPE = "system.dict.published";

    /** 监听队列名：构件命名规则 q.<consumerModule>.<eventType>，与 declareConsumerQueue 同源推导 */
    private static final String QUEUE_NAME = MessagingConstants.QUEUE_PREFIX + CONSUMER_MODULE + "." + EVENT_TYPE;

    /** 测试追踪锚点：发布 traceId，消费侧断言全链路透传 */
    private static final String TRACE_ANCHOR = "it-messaging-governance-trace";

    /** MQ 链路等待上限：覆盖消费投递与 test profile 快速重试（3 次亚秒级）+ 死信转发 + 死信消费落库 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** 幂等台账查询轮询间隔：死信落库为异步链路，200ms 步进轮询足够收敛 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** JDBC 模板：V5 种子登记行、幂等台账与死信台账的业务断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** RabbitAdmin：断言死信统一队列已随上下文声明成功 */
    private final AmqpAdmin amqpAdmin;

    /** RabbitTemplate：以治理构件装配的 JSON 转换器发布信封（生产/测试同源） */
    private final RabbitTemplate rabbitTemplate;

    /** 信封编解码器：发布侧工厂 + 消费侧解析的同源 Bean */
    private final EventEnvelopeCodec codec;

    /** Boot 全局定制 ObjectMapper：payload 线格式一致性断言的对照基准 */
    private final ObjectMapper objectMapper;

    /** 测试消费者 Bean：业务计数与放行锚点的真实载体 */
    private final ItDictPublishedConsumer consumer;

    /**
     * 构造器注入：Spring 6.2 测试构造器默认按注解识别（annotated 模式），须显式标注 @Autowired
     * 方可让 SpringExtension 从上下文解析各依赖；非空，来源为 fuyun-app test 上下文自动装配。
     *
     * @param jdbcTemplate   JDBC 模板，用于登记行/台账断言
     * @param amqpAdmin      RabbitAdmin，用于死信统一队列声明断言
     * @param rabbitTemplate RabbitTemplate，用于发布信封
     * @param codec          信封编解码器，用于创建合规信封
     * @param objectMapper   全局定制 ObjectMapper，用于 payload 线格式对照
     * @param consumer       测试消费者 Bean，承载业务计数与消费锚点
     */
    @Autowired
    MessagingGovernanceIT(
            JdbcTemplate jdbcTemplate,
            AmqpAdmin amqpAdmin,
            RabbitTemplate rabbitTemplate,
            EventEnvelopeCodec codec,
            ObjectMapper objectMapper,
            ItDictPublishedConsumer consumer) {
        this.jdbcTemplate = jdbcTemplate;
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.consumer = consumer;
    }

    /**
     * 测试内装配：消费队列必须经治理构件声明（禁止测试私建队列/交换机，M20 红线）——
     * 声明的同时完成 it 对 system.dict.published 的订阅自动登记（RabbitAdmin 幂等声明后监听容器启动）。
     */
    @TestConfiguration
    static class ItMessagingGovernanceConfig {

        /**
         * 声明 it 消费队列并绑定 fy.topic（走构件，事件未登记时启动即失败）。
         *
         * @param governance 消息治理构件，非空
         * @return 声明集合交 RabbitAdmin 幂等声明
         */
        @Bean
        Declarables itConsumerQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(new ConsumerQueueSpec(CONSUMER_MODULE, EVENT_TYPE));
        }

        /**
         * 测试消费者组件：@RabbitListener 注解驱动 + 容器 AUTO 确认（backend 宪法 A.5-5）。
         *
         * @param idempotencyService 消费幂等构件，非空
         * @param codec              信封编解码器，非空
         * @return 消费者实例
         */
        @Bean
        ItDictPublishedConsumer itDictPublishedConsumer(
                MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            return new ItDictPublishedConsumer(idempotencyService, codec);
        }
    }

    /**
     * 测试消费者：严格按幂等构件标准消费范式实现（跳过 / 业务 / 成功登记 / 失败收尾重抛），
     * 业务结果落在并发安全字段供断言线程读取。
     */
    static class ItDictPublishedConsumer {

        /** 已执行业务的帧计数（重复投递跳过帧不计数）：幂等拦截断言的业务结果载体 */
        final AtomicInteger businessCount = new AtomicInteger();

        /** 已执行业务的 eventId 集合：重复帧不得二次进入业务 */
        final Set<String> businessEventIds = ConcurrentHashMap.newKeySet();

        /** 首帧业务消费的 eventId：重复投递重发帧的构造来源 */
        final AtomicReference<String> firstEventId = new AtomicReference<>();

        /** 最近一帧业务消费的 eventId / traceId / payload：发布→消费一致性断言载体 */
        final AtomicReference<String> lastEventId = new AtomicReference<>();

        final AtomicReference<String> lastTraceId = new AtomicReference<>();
        final AtomicReference<JsonNode> lastPayload = new AtomicReference<>();

        /** 首帧业务消费放行闩：recordProcessed 落库完成后才放行（断言侧见放行即台账行已提交） */
        final CountDownLatch firstConsumed = new CountDownLatch(1);

        private final MessageIdempotencyService idempotencyService;

        private final EventEnvelopeCodec codec;

        /**
         * 全参构造器。
         *
         * @param idempotencyService 消费幂等构件，非空；来源：M20 治理构件装配
         * @param codec              信封编解码器，非空；来源：M20 治理构件装配
         */
        ItDictPublishedConsumer(MessageIdempotencyService idempotencyService, EventEnvelopeCodec codec) {
            this.idempotencyService = idempotencyService;
            this.codec = codec;
        }

        /**
         * 字典发布事件消费入口：raw Message 承接原始帧（消费容器工厂 SimpleMessageConverter 兜底，
         * 永不抛转换异常），UTF-8 解码为原文后经 codec 解析（消费侧信封合规校验①）。
         *
         * @param message 原始消息帧，非空；来源：fy.topic 路由的信封线格式
         */
        @RabbitListener(queues = QUEUE_NAME)
        void onDictPublished(Message message) {
            // String 承接语义由显式解码承载：原文进 codec，__TypeId__ 头不参与消费（CF-1 冻结约定）
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            // 标准消费范式①：重复投递（NX 抢占失败）直接返回跳过，即 AUTO 确认
            if (!idempotencyService.tryAcquire(envelope.eventId(), CONSUMER_MODULE)) {
                return;
            }
            // 信封五要素在业务前构造一次：成功登记与失败留痕共用（两处字段映射不漂移）
            ReceivedEventRecord record = new ReceivedEventRecord(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer(),
                    envelope.occurredAt(),
                    CONSUMER_MODULE);
            try {
                doBusiness(envelope);
                // 标准消费范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
                idempotencyService.recordProcessed(record);
                // 登记完成后放行：断言侧看到放行时台账行已提交
                firstConsumed.countDown();
            } catch (RuntimeException e) {
                // 标准消费范式③：释放前置键 + FAILED 留痕（W-6③ 双保留，异常链不遮蔽 e），上抛走有界重试进 fy.dlx
                idempotencyService.settleFailure(record, e);
                throw e;
            }
        }

        /**
         * 业务执行：poison 帧抛业务异常触发有界重试与死信链路；正常帧登记业务结果锚点。
         *
         * @param envelope 已解析的合规信封，非空
         */
        private void doBusiness(EventEnvelope envelope) {
            if (envelope.payload().path("poison").asBoolean(false)) {
                throw new IllegalStateException("模拟业务失败：poison 帧触发有界重试与死信链路");
            }
            businessCount.incrementAndGet();
            businessEventIds.add(envelope.eventId());
            // 首帧业务锚点只记一次：重复投递重发帧以此为幂等键来源
            firstEventId.compareAndSet(null, envelope.eventId());
            lastEventId.set(envelope.eventId());
            lastTraceId.set(envelope.traceId());
            lastPayload.set(envelope.payload());
        }
    }

    @Test
    @Order(1)
    @DisplayName("冻结登记断言：event_registry 四十条种子行齐全且全部 ACTIVE，system.dict.published 生产方为 system")
    void seedRegistryRowsAreFrozenAndActive() {
        // 总量口径：V5 七条 + V403 iot 一条 + V105 患者域八条（id 9–16）+ V605 billing 域八条
        // （id 17–24）+ V702 pharmacy 域七条（id 25–31）+ V204 outpatient 域九条（id 32–40；
        //   id 23/25/31 系 V605/V702 占位行经 V204 UPDATE 冻结，不增行）
        Integer totalRows =
                jdbcTemplate.queryForObject("SELECT count(*) FROM integration.event_registry", Integer.class);
        assertThat(totalRows).isEqualTo(40);
        Integer activeRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.event_registry WHERE status = ?",
                Integer.class,
                MessagingConstants.REGISTRY_STATUS_ACTIVE);
        assertThat(activeRows).isEqualTo(40);
        String producer = jdbcTemplate.queryForObject(
                "SELECT producer_module FROM integration.event_registry WHERE event_type = ?",
                String.class,
                EVENT_TYPE);
        assertThat(producer).isEqualTo("system");
    }

    @Test
    @Order(2)
    @DisplayName("发布→消费：信封经 fy.topic 路由被 it 队列消费，eventId/traceId/payload 线格式一致")
    void publishedEnvelopeIsConsumedWithConsistentIdentity() throws InterruptedException {
        DictPublishedPayload payload = new DictPublishedPayload("gender", 1);
        EventEnvelope envelope = codec.create(Clock.systemUTC(), "system", EVENT_TYPE, TRACE_ANCHOR, payload);

        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, envelope);

        assertThat(consumer.firstConsumed.await(LINK_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("首帧业务消费未在 %s 内放行", LINK_TIMEOUT)
                .isTrue();
        assertThat(consumer.businessCount.get()).isEqualTo(1);
        assertThat(consumer.lastEventId.get()).isEqualTo(envelope.eventId());
        assertThat(consumer.lastTraceId.get()).isEqualTo(TRACE_ANCHOR);
        // payload 线格式往返：发布 record 经全局 ObjectMapper 进线、消费侧以 JsonNode 出线，内容一致
        assertThat(consumer.lastPayload.get()).isEqualTo(objectMapper.valueToTree(payload));
    }

    @Test
    @Order(3)
    @DisplayName("重复投递被幂等拦截：同 eventId 再发一帧不进业务，received_event 该事件仅一行")
    void duplicateDeliveryIsInterceptedByIdempotency() throws InterruptedException {
        // 以首帧的 eventId + payload 重建重复投递帧（模拟 at-least-once 语义下的服务端重投）
        String duplicateEventId = consumer.firstEventId.get();
        JsonNode duplicatePayload = consumer.lastPayload.get();
        EventEnvelope duplicate = new EventEnvelope(
                duplicateEventId,
                Instant.now(Clock.systemUTC()),
                "system",
                EVENT_TYPE,
                MessagingConstants.ENVELOPE_DEFAULT_VERSION,
                TRACE_ANCHOR,
                duplicatePayload);
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, duplicate);

        // 顺序锚点：单队列单消费者按序处理，全新事件被消费即重复帧已被处理完毕（确定性等待，非盲等）
        DictPublishedPayload anchorPayload = new DictPublishedPayload("gender", 2);
        EventEnvelope anchor = codec.create(Clock.systemUTC(), "system", EVENT_TYPE, TRACE_ANCHOR, anchorPayload);
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, anchor);
        awaitBusinessCount(2);

        // 重复帧未进入业务：计数恰为 2、业务集合恰为两个不同 eventId
        assertThat(consumer.businessEventIds).containsExactlyInAnyOrder(duplicateEventId, anchor.eventId());
        // 幂等台账兜底验证：首帧 eventId + it 仅一行（复验 B2.2 申报① UUID 真库写入链路）
        Integer receivedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                UUID.fromString(duplicateEventId),
                CONSUMER_MODULE);
        assertThat(receivedRows).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("异常消息入死信：poison 帧重试耗尽经 fy.dlx 落库 PENDING 行，来源队列与原文留痕")
    void poisonMessageLandsInDeadLetterTable() {
        Map<String, Object> poisonPayload = Map.of("poison", true);
        EventEnvelope poison = codec.create(Clock.systemUTC(), "system", EVENT_TYPE, TRACE_ANCHOR, poisonPayload);
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, poison);

        Map<String, Object> deadLetter = awaitDeadLetterRow(poison.eventId());
        assertThat(deadLetter.get("source_queue")).isEqualTo(QUEUE_NAME);
        // 不设 x-dead-letter-routing-key：死信保留原始路由键（=事件类型），据此溯源
        assertThat(deadLetter.get("routing_key")).isEqualTo(EVENT_TYPE);
        assertThat(deadLetter.get("event_id")).isEqualTo(poison.eventId());
        assertThat(deadLetter.get("event_type")).isEqualTo(EVENT_TYPE);
        assertThat((String) deadLetter.get("payload_body")).contains("poison");
        assertThat((String) deadLetter.get("fail_reason")).isNotBlank();
        assertThat(deadLetter.get("status")).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
    }

    @Test
    @Order(5)
    @DisplayName("构件副作用：订阅自动登记生效（subscriber_modules 含 it），治理声明队列随上下文就绪")
    void governanceSideEffectsAreRegistered() {
        String subscribers = jdbcTemplate.queryForObject(
                "SELECT subscriber_modules FROM integration.event_registry WHERE event_type = ?",
                String.class,
                EVENT_TYPE);
        assertThat(subscribers).contains(CONSUMER_MODULE);

        // it 消费队列与死信统一队列均由 RabbitAdmin 随上下文声明成功（三交换机经消息实际路由已隐式验证）
        assertThat(amqpAdmin.getQueueProperties(QUEUE_NAME)).isNotNull();
        assertThat(amqpAdmin.getQueueProperties(MessagingConstants.QUEUE_DEAD_LETTER))
                .isNotNull();
    }

    /**
     * 轮询等待业务消费计数达到期望值：超时即以实际计数断言失败（附业务上下文便于排障）。
     *
     * @param expected 期望的业务计数
     * @throws InterruptedException 轮询等待被中断时触发；建议处理策略：终止用例并检查容器健康
     */
    private void awaitBusinessCount(int expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + LINK_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos && consumer.businessCount.get() < expected) {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(consumer.businessCount.get())
                .as("业务消费计数未在 %s 内达到 %s", LINK_TIMEOUT, expected)
                .isEqualTo(expected);
    }

    /**
     * 轮询等待死信台账出现指定 eventId 的留痕行并返回。
     *
     * <p>按来源队列收敛到 it 测试消费者自身行：PR-4 起 pharmacy 亦订阅 system.dict.published（V607
     * 字典水位消费），同一毒丸 eventId 会在 it 与 pharmacy 两队列各自死信落行（W-9 口径：dead_letter
     * 无唯一约束，同 eventId 可因不同消费者多次死信），不按 source_queue 过滤将命中他消费者行。
     *
     * @param eventId 死信所属信封 eventId，非空
     * @return 死信行字段视图（source_queue/routing_key/event_type/event_id/payload_body/fail_reason/status）
     */
    private Map<String, Object> awaitDeadLetterRow(String eventId) {
        long deadlineNanos = System.nanoTime() + LINK_TIMEOUT.toNanos();
        List<Map<String, Object>> rows = List.of();
        while (System.nanoTime() < deadlineNanos) {
            rows = jdbcTemplate.queryForList(
                    "SELECT source_queue, routing_key, event_type, event_id, payload_body, fail_reason, status"
                            + " FROM integration.dead_letter WHERE event_id = ? AND source_queue = ?",
                    eventId,
                    QUEUE_NAME);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(rows).as("死信未在 %s 内落库：event_id=%s", LINK_TIMEOUT, eventId).isNotEmpty();
        return rows.get(0);
    }
}

package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.system.api.DictPublishedPayload;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.dto.DictItemCreateRequest;
import com.fuyun.system.dto.DictTypeCreateRequest;
import com.fuyun.system.service.IDictItemService;
import com.fuyun.system.service.IDictTypeService;
import com.fuyun.system.service.IDictVersionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 字典发布广播端到端集成测试（B3.3 交付，BRIEF-PR3-01 §5.2 六步断言 + B3.2 审核 M-4 负路径补断言）：
 * 真实打通「类型/版本/条目维护 → publish 事务提交 → AFTER_COMMIT 信封发布 → fy.topic 路由 →
 * system 消费队列 → DictPublishedListener 标准幂等范式 → received_event 台账」全链。
 *
 * <p>业务意图：验证字典域发布侧与消费侧的真实协同——V5 种子登记行在位、代理调用 publish 的事务
 * 提交时机（AFTER_COMMIT 才广播）、消费幂等台账 PROCESSED 落库、订阅自动登记副作用、同 eventId
 * 重投被 D-7 回查路径拦截（NX 失败 + 台账已处理 → 跳过，行数仍为 1）、业务读口径（当前 PUBLISHED
 * 版本 + 旧版本自动 DEPRECATED）；M-4 负路径断言：发布被拒（非 DRAFT，409 SYS-1013）事务回滚后
 * 广播队列无新消息（received_event 无新行）。
 *
 * <p>容器三件套与 {@link SmokeStackIT} 完全同款（tag 与 deploy compose 严格一致 + it/rabbitmq.conf
 * 挂载 + static 类级共享 + @ServiceConnection）；HMAC 密钥经 @DynamicPropertySource 注入测试资产
 * 假密钥（非真实凭证）。publish 经注入的 service 代理调用（@Transactional 生效，事务提交后触发
 * AFTER_COMMIT 发布，BRIEF-PR3-01 §5.2 口径）；幂等重投帧以已消费 eventId 手工经 RabbitTemplate
 * 重发同构信封。步骤断言按序执行（@Order 串联，字典数据与台账跨步累积属业务链路语义）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DictBroadcastIT {

    /** TimescaleDB 容器：event_registry / received_event / 字典三表的断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：消费幂等 Redis 前置键（D-7 NX + 回查）的真实存储 */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：fy.topic 路由与 q.system.system.dict.published 消费队列的真实载体 */
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-dict.conf");

    /** 测试资产假密钥（57 字符，仅具 IT 意义，与任何真实凭证无关；真实密钥只经环境变量注入） */
    private static final String TEST_HMAC_SECRET = "it-only-fake-hmac-secret-0123456789abcdef0123456789abcdef";

    /**
     * 注入测试用 HMAC 密钥：SecurityProperties（fuyun.security.*）密钥缺失即启动 fail-fast。
     *
     * @param registry 动态属性注册器，非空；来源：Spring TestContext 框架
     */
    @DynamicPropertySource
    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("fuyun.security.token-hmac-secret", () -> TEST_HMAC_SECRET);
    }

    /** 广播事件类型：V5 种子登记行（M01 Spec §7） */
    private static final String EVENT_TYPE = "system.dict.published";

    /** 本批字典类型编码（小写点分格式约束内；容器级隔离不与既有数据冲突） */
    private static final String TYPE_CODE = "it.broadcast.gender";

    /** 首版本条目编码：业务读口径断言的对照值 */
    private static final String ITEM_CODE = "M";

    /** 消费幂等台账轮询上限：覆盖消费投递与 recordProcessed 落库的异步窗口 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** 台账查询轮询间隔：异步消费链路 200ms 步进足够收敛（同 MessagingGovernanceIT 姿态） */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 首版本 eventId 持有器：幂等重投帧的构造来源（静态承载跨用例链路状态） */
    static final AtomicReference<String> FIRST_EVENT_ID = new AtomicReference<>();

    /** HTTP 客户端：步骤6 业务读口径经真实 HTTP 全链验证 */
    private final TestRestTemplate restTemplate;

    /** JSON 解析器：登录与字典读响应体断言 */
    private final ObjectMapper objectMapper;

    /** JDBC 模板：event_registry / received_event / dict_version 台账与状态断言通道 */
    private final JdbcTemplate jdbcTemplate;

    /** RabbitTemplate：以治理装配的 JSON 转换器重发幂等重投帧（生产/测试同源） */
    private final RabbitTemplate rabbitTemplate;

    /** 字典域三服务（代理调用，@Transactional 提交后触发 AFTER_COMMIT 发布） */
    private final IDictTypeService dictTypeService;

    private final IDictVersionService dictVersionService;

    private final IDictItemService dictItemService;

    /**
     * 构造器注入：Spring 6.2 测试构造器默认按注解识别（annotated 模式），须显式标注 @Autowired
     * 方可让 SpringExtension 从上下文解析各依赖；非空，来源为 fuyun-app test 上下文自动装配。
     *
     * @param restTemplate       TestRestTemplate，RANDOM_PORT 全链 HTTP 客户端
     * @param objectMapper       全局定制 ObjectMapper，响应体 JSON 解析
     * @param jdbcTemplate       JDBC 模板，台账与字典状态断言
     * @param rabbitTemplate     RabbitTemplate，幂等重投帧发送
     * @param dictTypeService    字典类型服务（代理 Bean），类型创建
     * @param dictVersionService 字典版本服务（代理 Bean），版本创建与发布
     * @param dictItemService    字典条目服务（代理 Bean），条目新增
     */
    @Autowired
    DictBroadcastIT(
            TestRestTemplate restTemplate,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            RabbitTemplate rabbitTemplate,
            IDictTypeService dictTypeService,
            IDictVersionService dictVersionService,
            IDictItemService dictItemService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.dictTypeService = dictTypeService;
        this.dictVersionService = dictVersionService;
        this.dictItemService = dictItemService;
    }

    @Test
    @Order(1)
    @DisplayName("步骤1：event_registry 含 system.dict.published 行且 ACTIVE、生产方为 system")
    void registryRowIsActiveForDictPublishedEvent() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, producer_module FROM integration.event_registry WHERE event_type = ?", EVENT_TYPE);
        assertThat(row.get("status")).isEqualTo(MessagingConstants.REGISTRY_STATUS_ACTIVE);
        assertThat(row.get("producer_module")).isEqualTo("system");
    }

    @Test
    @Order(2)
    @DisplayName("步骤2：建类型→草稿版本→条目→publish，事务提交后广播被 system 消费者落 PROCESSED 台账")
    void publishDraftVersionBroadcastsAndConsumerRecordsLedger() {
        // 代理调用字典域三服务走真实事务链：类型 → 版本（默认 DRAFT）→ 条目 → 发布
        dictTypeService.createType(new DictTypeCreateRequest(TYPE_CODE, "IT 广播性别字典", false, null));
        dictVersionService.createVersion(TYPE_CODE);
        dictItemService.addItem(versionId(1), new DictItemCreateRequest(ITEM_CODE, "男", null, 0));
        dictVersionService.publish(versionId(1));

        // 轮询断言：真实 DictPublishedListener 消费成功，标准范式 recordProcessed 落 PROCESSED 台账
        awaitReceivedEventCount(1);
        String eventId = jdbcTemplate
                .queryForObject(
                        "SELECT event_id FROM integration.received_event WHERE consumer_module = ?",
                        UUID.class,
                        "system")
                .toString();
        FIRST_EVENT_ID.set(eventId);
    }

    @Test
    @Order(3)
    @DisplayName("步骤3：event_registry 该事件 subscriber_modules 含 system（订阅自动登记副作用）")
    void subscriberModuleIsAutoRegistered() {
        String subscribers = jdbcTemplate.queryForObject(
                "SELECT subscriber_modules FROM integration.event_registry WHERE event_type = ?",
                String.class,
                EVENT_TYPE);
        assertThat(subscribers).contains("system");
    }

    @Test
    @Order(4)
    @DisplayName("步骤4（M-4 负路径）：已发布版本再 publish 被 409 SYS-1013 拒绝，事务回滚广播不出")
    void rejectedRepublishRollsBackWithoutBroadcast() {
        // 状态机前置校验拒绝（仅 DRAFT 可发布）：事务回滚 → AFTER_COMMIT 不触发 → 队列无新消息
        Long v1Id = versionId(1);
        try {
            dictVersionService.publish(v1Id);
            throw new AssertionError("已发布版本重复 publish 必须被拒绝");
        } catch (BizException expected) {
            assertThat(expected.getErrorCode()).isEqualTo(SystemErrorCode.DICT_VERSION_NOT_PUBLISHABLE);
            assertThat(expected.getHttpStatus().value()).isEqualTo(409);
        }
        // 即时核对：拒绝路径当下不产生任何台账新行（终局证明由步骤5 的锚点事件给出）
        assertThat(receivedEventCount()).isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("步骤5：同 eventId 幂等重投被 D-7 回查跳过（行数仍为 1），且步骤4 拒绝路径确认无广播")
    void redeliveryIsInterceptedByIdempotencyLedger() {
        // 以已消费 eventId 重建同构信封手工重发（模拟 at-least-once 服务端重投）
        EventEnvelope redelivered = new EventEnvelope(
                FIRST_EVENT_ID.get(),
                Instant.now(),
                "system",
                EVENT_TYPE,
                MessagingConstants.ENVELOPE_DEFAULT_VERSION,
                "dict-broadcast-it-redelivery",
                objectMapper.valueToTree(new DictPublishedPayload(TYPE_CODE, 1)));
        rabbitTemplate.convertAndSend(MessagingConstants.EXCHANGE_TOPIC, EVENT_TYPE, redelivered);

        // 锚点事件：发布第二版本（真实广播）。单队列单消费者按序处理——锚点台账行可见即重投帧已被处理完毕
        dictVersionService.createVersion(TYPE_CODE);
        dictItemService.addItem(versionId(2), new DictItemCreateRequest(ITEM_CODE, "男", null, 0));
        dictVersionService.publish(versionId(2));
        awaitReceivedEventCount(2);

        // 幂等台账终局断言：重投 eventId 行数仍为 1（D-7：NX 失败 + 台账已处理 → 跳过）
        Integer redeliveredRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event WHERE event_id = ? AND consumer_module = ?",
                Integer.class,
                UUID.fromString(FIRST_EVENT_ID.get()),
                "system");
        assertThat(redeliveredRows).isEqualTo(1);
        // 步骤4 负路径终局证明：台账恰为 2 行（两版本各一条）——若拒绝路径误发广播，此处必为 3
        assertThat(receivedEventCount()).isEqualTo(2);
    }

    @Test
    @Order(6)
    @DisplayName("步骤6：业务读口径——无 version 返回当前 PUBLISHED 版本，指定 version=1 返回 DEPRECATED")
    void businessReadReturnsPublishedVersionAndDeprecatesOld() throws Exception {
        // 登录取令牌（真实认证链路），受保护读端点需 Bearer 令牌
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> loginResponse = restTemplate.exchange(
                "/api/v1/system/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        objectMapper
                                .createObjectNode()
                                .put("loginName", "admin")
                                .put("password", "Fuyun@2026")
                                .toString(),
                        loginHeaders),
                String.class);
        String accessToken = objectMapper
                .readTree(loginResponse.getBody())
                .path("accessToken")
                .asText();
        assertThat(accessToken).isNotBlank();

        // 无 version：当前 PUBLISHED 版本（第二版本），条目全量返回
        JsonNode current = getDictVersion(TYPE_CODE, null, accessToken);
        assertThat(current.path("version").asInt()).isEqualTo(2);
        assertThat(current.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(current.path("items").toString()).contains(ITEM_CODE);

        // 指定 version=1：任意状态回溯口径，旧版本已被新发布自动置 DEPRECATED
        JsonNode oldVersion = getDictVersion(TYPE_CODE, 1, accessToken);
        assertThat(oldVersion.path("version").asInt()).isEqualTo(1);
        assertThat(oldVersion.path("status").asText()).isEqualTo("DEPRECATED");

        // 库内状态终局核对：v1=DEPRECATED、v2=PUBLISHED（同一 type 同一时刻仅一个 PUBLISHED）
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM system.dict_version WHERE id = ?", String.class, versionId(1)))
                .isEqualTo("DEPRECATED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM system.dict_version WHERE id = ?", String.class, versionId(2)))
                .isEqualTo("PUBLISHED");
    }

    /**
     * 按版本号查字典版本 ID（V301 uk(dict_type_id, version) 唯一，取单值）。
     *
     * @param version 版本号（1 起），非空
     * @return 版本行 ID，非空
     */
    private Long versionId(int version) {
        return jdbcTemplate.queryForObject(
                "SELECT v.id FROM system.dict_version v JOIN system.dict_type t ON v.dict_type_id = t.id"
                        + " WHERE t.type_code = ? AND v.version = ?",
                Long.class,
                TYPE_CODE,
                version);
    }

    /**
     * 轮询等待 received_event（consumer_module=system）行数达到期望值。
     *
     * @param expected 期望行数，非负
     */
    private void awaitReceivedEventCount(int expected) {
        long deadlineNanos = System.nanoTime() + LINK_TIMEOUT.toNanos();
        int actual = receivedEventCount();
        while (System.nanoTime() < deadlineNanos && actual < expected) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            actual = receivedEventCount();
        }
        assertThat(actual)
                .as("received_event 行数未在 %s 内达到 %s", LINK_TIMEOUT, expected)
                .isEqualTo(expected);
    }

    /**
     * 查询 system 消费者的幂等台账行数（仅 PROCESSED 行，P0 唯一写入值）。
     *
     * @return 当前行数，非负
     */
    private int receivedEventCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event" + " WHERE consumer_module = ? AND status = ?",
                Integer.class,
                "system",
                MessagingConstants.RECEIVED_STATUS_PROCESSED);
        return count != null ? count : 0;
    }

    /**
     * 携带 Bearer 令牌读取字典版本（GET /api/v1/system/dicts/{type}?version=）。
     *
     * @param typeCode    字典类型编码，非空
     * @param version     版本号，可空；null 不携带查询参数（当前 PUBLISHED 口径）
     * @param accessToken 访问令牌，非空
     * @return 响应体 JSON 树，非空
     * @throws Exception 响应体 JSON 解析失败时触发；建议处理策略：终止用例并检查服务端响应
     */
    private JsonNode getDictVersion(String typeCode, Integer version, String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        String uri = "/api/v1/system/dicts/" + typeCode + (version != null ? "?version=" + version : "");
        ResponseEntity<String> response =
                restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).as("字典读必须成功：%s", uri).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }
}

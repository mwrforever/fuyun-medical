package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.system.constants.SystemMessagingConstants;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * 角色管理写链集成测试（PR-4F 修复环 2026-10-07）：以真实三中间件锁定五路评审四项修复的
 * 业务语义——评审 A-I1（启停会话语义：停用即踢出 + 同值短路不踢）、C-I1（并发覆写串行化：
 * 角色行 FOR UPDATE 后终态=后写者载荷而非并集）、C-I2（覆写插入行 created_by 操作人注入）、
 * B-I1（治理命名队列坏载荷经 settleFailure 留痕 + 有界重试耗尽进死信，禁静默 ack）。
 *
 * <p>断言通道均为业务结果面：会话存续以「受保护端点 401 与否」判别（踢出=会话键被删必 401；
 * 存续=过认证层落 403/2xx，状态码不锁 403 防拦截层演进破测试）；并发终态以真库绑定码集
 * 判别；坏载荷以 integration.received_event FAILED 台账行与 dead_letter 死信行判别
 * （MessagingGovernanceIT 幂等/死信先例同款）。
 *
 * <p>并发用例画像说明（评审 C-I1）：8 轮 latch 对拍双写，无行锁时读-diff-写基于语句时点
 * 快照交错提交，终态大概率呈现两载荷并集；行锁串行化后每轮终态必为单载荷（后写者完整
 * 覆盖）。轮次给足回归显现窗口，锁语义回归（FOR UPDATE 被移除）时高概率红。
 *
 * <p>容器三件套类级独占（GC9 红线，RbacMatrixIT 同型：tag 与 deploy compose 严格一致 +
 * it/rabbitmq.conf 挂载 + @ServiceConnection）；测试假密钥经 {@link FuyunStackITBase}
 * @DynamicPropertySource 注入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleAdminWriteChainIT extends FuyunStackITBase {

    /** TimescaleDB 容器：管理端点写链与绑定行断言目标库；tag 与 deploy compose 严格一致 */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器：登录会话键（fy:system:session:{sid}）与幂等前置键的真实存储 */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器：矩阵变更广播/治理队列消费与坏载荷死信链路的真实 broker */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"),
                    "/etc/rabbitmq/conf.d/20-fuyun-roleadmin-it.conf");

    /** V303 种子 admin 用户 id：AuthTokenContextHolder 注入的操作人（session.userId 字符串形态） */
    private static final String ADMIN_USER_ID_TEXT = "1";

    /** 会话踢出链收敛等待上限：覆盖 MQ 消费 + 会话键清理（轮询 200ms 步进） */
    private static final long LINK_TIMEOUT_MILLIS = 15_000L;

    /** 轮询步进：异步链路（消费/死信落库）收敛等待间隔 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 并发覆写对拍轮数：给「无行锁并集」回归足够的显现窗口（评审 C-I1 画像） */
    private static final int CONCURRENCY_ROUNDS = 8;

    /** RabbitTemplate：坏载荷信封以治理构件装配的 JSON 转换器发布（生产/测试同源） */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 信封编解码器：坏载荷信封创建（信封五要素合规、载荷类型不符契约） */
    @Autowired
    private EventEnvelopeCodec codec;

    /**
     * 行级归因注入（评审 C-I2）：管理台覆写新增绑定行，created_by 落登录操作者而非库默认
     * 'system'——真实 HTTP 链路（AuthTokenInterceptor 写入操作人上下文）全链验证。
     */
    @Test
    @Order(1)
    @DisplayName("覆写插入行归因：admin 覆写 CASHIER 增绑码，新绑定行 created_by=操作者（非 'system'）")
    void overwriteInsertsBindingRowWithOperatorAttribution() throws Exception {
        String adminToken = loginToken(ADMIN_LOGIN_NAME);
        // 目标码：选一颗未绑定 CASHIER 的 ELEMENT 码（种子演进无关，动态取码）
        String newCode = jdbcTemplate.queryForObject(
                "SELECT p.perm_code FROM system.sys_permission p"
                        + " WHERE p.perm_type = 'ELEMENT' AND p.deleted = 0"
                        + " AND NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp"
                        + " JOIN system.sys_role r ON r.id = rp.role_id"
                        + " WHERE rp.permission_id = p.id AND r.role_code = 'CASHIER' AND rp.deleted = 0)"
                        + " ORDER BY p.perm_code LIMIT 1",
                String.class);
        // 保留式覆写（管理台真实操作形态）：现绑码集 + 新码，防清空其他绑定干扰后续用例
        JsonNode roleRow = currentRoleRow(adminToken, "CASHIER");
        ArrayNode payloadCodes = objectMapper.createArrayNode();
        roleRow.path("permCodes").forEach(code -> payloadCodes.add(code.asText()));
        payloadCodes.add(newCode);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("permCodes", payloadCodes);

        ResponseEntity<String> response =
                bearerExchange("/api/v1/system/roles/CASHIER/permissions", HttpMethod.PUT, adminToken, body);
        assertThat(response.getStatusCode().value()).as("覆写应 200").isEqualTo(200);

        // 新绑定行行级归因：操作人=登录会话 userId（admin=1），未注入修复前恒为库默认 'system'
        Map<String, Object> insertedRow = jdbcTemplate.queryForMap(
                "SELECT rp.created_by FROM system.sys_role_permission rp"
                        + " JOIN system.sys_role r ON r.id = rp.role_id"
                        + " JOIN system.sys_permission p ON p.id = rp.permission_id"
                        + " WHERE r.role_code = 'CASHIER' AND rp.deleted = 0 AND p.perm_code = ?",
                newCode);
        assertThat(String.valueOf(insertedRow.get("created_by")))
                .as("覆写新增绑定行 created_by 应为登录操作者（评审 C-I2）")
                .isEqualTo(ADMIN_USER_ID_TEXT);
    }

    /**
     * 并发覆写串行化（评审 C-I1）：同角色双线程对拍全量覆写，终态必须恰为两载荷之一
     * （last-writer-wins）——无行锁时读-diff-写交错终态为两载荷并集（权限面静默扩张）。
     */
    @Test
    @Order(2)
    @DisplayName("并发同角色覆写串行化：8 轮对拍终态恒为单载荷（后写者覆盖），绝不为两载荷并集")
    void concurrentOverwritesSerializeToLastWriterPayload() throws Exception {
        String adminToken = loginToken(ADMIN_LOGIN_NAME);
        // 三颗互异且未绑 CASHIER 的 ELEMENT 码：X/Y 为对拍载荷，Z 为每轮复位态
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT p.perm_code FROM system.sys_permission p"
                        + " WHERE p.perm_type = 'ELEMENT' AND p.deleted = 0"
                        + " AND NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp"
                        + " JOIN system.sys_role r ON r.id = rp.role_id"
                        + " WHERE rp.permission_id = p.id AND r.role_code = 'CASHIER' AND rp.deleted = 0)"
                        + " ORDER BY p.perm_code LIMIT 3",
                String.class);
        assertThat(codes).as("种子面应提供三颗可用 ELEMENT 码").hasSize(3);
        String codeX = codes.get(0);
        String codeY = codes.get(1);
        String codeZ = codes.get(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= CONCURRENCY_ROUNDS; round++) {
                // 每轮复位到第三态 {Z}：保证两线程的 diff 都含插入侧（并集回归的显现前提）
                assertThat(putPermCodes(adminToken, List.of(codeZ))
                                .getStatusCode()
                                .value())
                        .as("复位态覆写应 200（第 %d 轮）", round)
                        .isEqualTo(200);
                // latch 对拍双写：两请求尽量同拍发出，命中读-diff-写交错窗口
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> writes = new ArrayList<>();
                for (String payloadCode : List.of(codeX, codeY)) {
                    writes.add(executor.submit(() -> {
                        start.await();
                        return putPermCodes(adminToken, List.of(payloadCode))
                                .getStatusCode()
                                .value();
                    }));
                }
                start.countDown();
                for (Future<Integer> write : writes) {
                    assertThat(write.get(LINK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                            .as("并发覆写请求应全部 200（第 %d 轮）", round)
                            .isEqualTo(200);
                }
                // 终态断言：恰一颗（后写者），Z 必被清——并集（X+Y 同存）即行锁回归红
                List<String> finalCodes = jdbcTemplate.queryForList(
                        "SELECT p.perm_code FROM system.sys_role_permission rp"
                                + " JOIN system.sys_role r ON r.id = rp.role_id"
                                + " JOIN system.sys_permission p ON p.id = rp.permission_id"
                                + " WHERE r.role_code = 'CASHIER' AND rp.deleted = 0"
                                + " AND p.perm_code IN (?, ?, ?)",
                        String.class,
                        codeX,
                        codeY,
                        codeZ);
                assertThat(finalCodes)
                        .as("并发覆写终态应恰为单载荷（第 %d 轮，评审 C-I1 行锁串行化）", round)
                        .hasSize(1);
                assertThat(finalCodes.get(0)).isIn(codeX, codeY);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 启停会话语义（评审 A-I1）：停用即踢出受影响角色全部在线会话（旧令牌 401）；同值提交
     * 短路零事件不踢（重复 PUT ACTIVE 不再全员登出）。会话存续以受保护端点 401 与否判别。
     */
    @Test
    @Order(3)
    @DisplayName("启停会话语义：同值 PUT 不踢会话；停用即踢出（旧令牌转 401），复启用恢复")
    void statusToggleKicksSessionsOnlyOnRealChange() throws Exception {
        String adminToken = loginToken(ADMIN_LOGIN_NAME);
        String nurseToken = loginToken("nursedemo");

        // 前置：NURSE 处于种子 ACTIVE 态，会话可用（过认证层，403 亦可——状态码不锁拦截层形态）
        assertThat(probeSessionAlive(nurseToken)).as("登录会话初始应存活").isTrue();

        // 同值短路：ACTIVE→ACTIVE 零写零事件——会话不得被踢（修复前无变化保存也全员登出）
        assertThat(putStatus(adminToken, "NURSE", "ACTIVE").getStatusCode().value())
                .as("同值启停应 200")
                .isEqualTo(200);
        assertThat(probeSessionAlive(nurseToken)).as("同值启停不得踢出在线会话（评审 A-I1）").isTrue();

        // 停用即踢出：事件链（广播→治理队列→evictSessionsByRoles）收敛后旧令牌 401
        assertThat(putStatus(adminToken, "NURSE", "DISABLED").getStatusCode().value())
                .as("停用应 200")
                .isEqualTo(200);
        awaitSessionEvicted(nurseToken);

        // 复启用（真值变更，事件链正常；恢复种子态不遗留停用角色）
        assertThat(putStatus(adminToken, "NURSE", "ACTIVE").getStatusCode().value())
                .as("复启用应 200")
                .isEqualTo(200);
    }

    /**
     * 坏载荷死信链（评审 B-I1）：信封合规而载荷类型不符契约的帧投递治理命名队列——修复前
     * 受检 JsonProcessingException 透出 catch(RuntimeException)，settleFailure 不执行（无
     * FAILED 台账）；修复后包成 IllegalStateException 走失败收尾 + 有界重试耗尽进 fy.dlx。
     */
    @Test
    @Order(4)
    @DisplayName("坏载荷失败收尾留痕：FAILED 台账行（retry_count=3）+ 死信落库，绝无静默 ack")
    void badPayloadSettlesFailureAndLandsInDeadLetter() throws Exception {
        // roleCode 为嵌套对象：treeToValue 无法还原 String 契约字段（信封五要素本身合规）
        EventEnvelope badPayload = codec.create(
                Clock.systemUTC(),
                SystemMessagingConstants.MODULE,
                SystemMessagingConstants.EVENT_PERMISSION_CHANGED,
                "it-roleadmin-badpayload-trace",
                Map.of("roleCode", Map.of("bad", true)));
        rabbitTemplate.convertAndSend(
                MessagingConstants.EXCHANGE_TOPIC, SystemMessagingConstants.EVENT_PERMISSION_CHANGED, badPayload);

        // 死信留痕先行收敛：重试耗尽经 fy.dlx 落库仅在全部投递尝试完成后发生，以其为收敛锚
        // 可保证随后读到的 FAILED 台账行 retry_count 已累加至终值（MessagingGovernanceIT 同款口径）
        Map<String, Object> deadLetter = awaitDeadLetterRow(badPayload.eventId());
        assertThat(deadLetter.get("source_queue")).isEqualTo(SystemMessagingConstants.QUEUE_PERMISSION_CHANGED);
        assertThat(deadLetter.get("event_type")).isEqualTo(SystemMessagingConstants.EVENT_PERMISSION_CHANGED);
        assertThat(String.valueOf(deadLetter.get("fail_reason"))).isNotBlank();
        assertThat(deadLetter.get("status")).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);

        // 失败台账：FAILED 行经 settleFailure 留痕，三次投递尝试逐次累加 retry_count（评审 B-I1
        // 核心——修复前受检异常透出 catch(RuntimeException) 使失败收尾不执行，此行根本不出现）
        Map<String, Object> failedRow = awaitReceivedEventRow(badPayload.eventId());
        assertThat(String.valueOf(failedRow.get("status"))).isEqualTo(MessagingConstants.RECEIVED_STATUS_FAILED);
        assertThat(((Number) failedRow.get("retry_count")).intValue())
                .as("test profile 有界重试 3 次逐次留痕")
                .isEqualTo(3);
        assertThat(String.valueOf(failedRow.get("fail_reason"))).contains("权限矩阵变更载荷与契约不符");
    }

    /**
     * 会话存续探针：以受保护管理端点探测令牌是否过认证层（401=会话键被删即已踢出；
     * 403/2xx=会话存续——状态码语义归 401 判别，不锁拦截层演进形态）。
     *
     * @param token 被探测的 Bearer 令牌原文，非空
     * @return true=会话存续；false=已 401（会话被踢出）
     */
    private boolean probeSessionAlive(String token) {
        return bearerExchange("/api/v1/system/roles", HttpMethod.GET, token, null)
                        .getStatusCode()
                        .value()
                != 401;
    }

    /**
     * 轮询等待会话被踢出：事件链（AFTER_COMMIT 广播→治理队列消费→会话键清理）收敛后探针转
     * 401；超窗仍存活即断言失败（踢出链回归红）。
     *
     * @param token 被探测的 Bearer 令牌原文，非空
     */
    private void awaitSessionEvicted(String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT_MILLIS;
        while (probeSessionAlive(token) && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(probeSessionAlive(token))
                .as("停用后受影响角色会话应在 %dms 内被踢出（评审 A-I1）", LINK_TIMEOUT_MILLIS)
                .isFalse();
    }

    /**
     * 轮询等待 FAILED 台账行出现并返回（坏载荷失败收尾留痕断言载体）。
     *
     * @param eventId 坏帧信封 eventId，非空
     * @return received_event 行字段视图（status/retry_count/fail_reason）
     */
    private Map<String, Object> awaitReceivedEventRow(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT_MILLIS;
        List<Map<String, Object>> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = jdbcTemplate.queryForList(
                    "SELECT status, retry_count, fail_reason FROM integration.received_event"
                            + " WHERE event_id = ? AND consumer_module = ?",
                    UUID.fromString(eventId),
                    SystemMessagingConstants.MODULE);
            // 收敛条件含 retry_count 终值：死信落库与末次留痕提交为相邻独立事务，防读到 3 次
            // 尝试尚未累加完的中间态（评审 B-I1 断言确定性）
            if (!rows.isEmpty()
                    && MessagingConstants.RECEIVED_STATUS_FAILED.equals(
                            String.valueOf(rows.get(0).get("status")))
                    && ((Number) rows.get(0).get("retry_count")).intValue() >= 3) {
                return rows.get(0);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(rows)
                .as("坏载荷应在 %dms 内留下 FAILED 台账行（评审 B-I1）", LINK_TIMEOUT_MILLIS)
                .isNotEmpty();
        return rows.get(0);
    }

    /**
     * 轮询等待死信台账出现指定 eventId 的留痕行并返回（MessagingGovernanceIT 同款收敛口径）。
     *
     * @param eventId 坏帧信封 eventId，非空
     * @return 死信行字段视图（source_queue/event_type/fail_reason/status）
     */
    private Map<String, Object> awaitDeadLetterRow(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT_MILLIS;
        List<Map<String, Object>> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = jdbcTemplate.queryForList(
                    "SELECT source_queue, event_type, fail_reason, status FROM integration.dead_letter"
                            + " WHERE event_id = ? AND source_queue = ?",
                    eventId,
                    SystemMessagingConstants.QUEUE_PERMISSION_CHANGED);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(rows)
                .as("坏载荷应在 %dms 内经 fy.dlx 落死信（评审 B-I1）", LINK_TIMEOUT_MILLIS)
                .isNotEmpty();
        return rows.get(0);
    }

    /**
     * 查询指定角色当前管理台视图行（GET roles 的 JSON 数组中按 roleCode 过滤）。
     *
     * @param adminToken 管理员 Bearer 令牌，非空
     * @param roleCode   角色编码，非空
     * @return 角色行 JSON（roleCode/roleName/status/permCodes），非空
     * @throws IllegalStateException 响应体非合法 JSON 或角色行缺失
     */
    private JsonNode currentRoleRow(String adminToken, String roleCode) throws Exception {
        ResponseEntity<String> response = bearerExchange("/api/v1/system/roles", HttpMethod.GET, adminToken, null);
        JsonNode roles = objectMapper.readTree(response.getBody());
        for (JsonNode role : roles) {
            if (roleCode.equals(role.path("roleCode").asText())) {
                return role;
            }
        }
        throw new IllegalStateException("管理台角色清单缺失目标角色行：" + roleCode);
    }

    /**
     * 矩阵全量覆写出网（单码载荷形态：并发对拍与复位态共用）。
     *
     * @param adminToken 管理员 Bearer 令牌，非空
     * @param permCodes  目标码全集（覆写语义：载荷即终态），非空
     * @return 原始响应（状态码断言载体）
     */
    private ResponseEntity<String> putPermCodes(String adminToken, List<String> permCodes) {
        ArrayNode codes = objectMapper.createArrayNode();
        permCodes.forEach(codes::add);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("permCodes", codes);
        return bearerExchange("/api/v1/system/roles/CASHIER/permissions", HttpMethod.PUT, adminToken, body);
    }

    /**
     * 角色启停出网。
     *
     * @param adminToken 管理员 Bearer 令牌，非空
     * @param roleCode   目标角色编码，非空
     * @param status     目标态（ACTIVE/DISABLED），非空
     * @return 原始响应（状态码断言载体）
     */
    private ResponseEntity<String> putStatus(String adminToken, String roleCode, String status) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", status);
        return bearerExchange("/api/v1/system/roles/" + roleCode + "/status", HttpMethod.PUT, adminToken, body);
    }

    /**
     * 携带 Bearer 令牌的 JSON 交换助手（RbacMatrixIT bearerJson 同型）。
     *
     * @param path   目标 URI（/api/v1 前缀），非空
     * @param method HTTP 方法，非空
     * @param token  Bearer 令牌原文，非空；经 Authorization 头注入（禁入日志）
     * @param body   请求体对象（ObjectNode），可空=无体（GET）
     * @return 原始字符串响应
     */
    private ResponseEntity<String> bearerExchange(String path, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}

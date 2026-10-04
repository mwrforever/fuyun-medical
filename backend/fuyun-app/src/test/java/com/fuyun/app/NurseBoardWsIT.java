package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-3 验收锚点⑥（护士站大屏真栈：REST 快照四段 + WS 主通道推送 + tick 心跳）：board REST
 * 快照四段断言（床位墙/任务逾期/出入院动态/危急值空段占位）+ WS 订阅
 * {@code /topic/nursing/board/{wardId}} 真实 STOMP 客户端收床位动态推送帧（BED_PATIENT）+
 * tick 心跳观测（overdue 任务首标驱动 TASK_OVERDUE 推送——延迟档位 60s 自续期链路正常路径）。
 *
 * <p><b>WS 测试形态结论（先实测先例 IotTelemetryPipelineIT）</b>：仓内确有真 STOMP 客户端订阅
 * 先例（WebSocketStompClient + StandardWebSocketClient，令牌承载 CONNECT 帧 Authorization 原生
 * 头走帧级鉴权全链）——本 IT 同款形态连 {@code /ws/nursing}，无降级条款适用。床位动态推送触发面
 * =admitted 事件信封直投（nursing 消费→投影落位→BED_PATIENT 帧出站；投影写入单一面真链路锚归
 * WardPatientRetirementIT 承载，本 IT 聚焦推送通道）。大屏读面夹具（投影行/逾期任务行）经
 * jdbcTemplate 直插——board 为纯读聚合面，NursingVitalSignFlowIT 夹具批准口径。
 *
 * <p><b>tick 观测（Task 9 minor③ 口径：正常路径，勿造 DLX 场景）</b>：预置 PENDING 任务
 * plan_time=now−40min（越首逾阈值 30 分钟），等待延迟档位自然到期（60s TTL 自续期）驱动首标
 * +TASK_OVERDUE 帧；等待窗 150s 覆盖完整档位周期。
 *
 * <p>容器三件套类级独占（GC9 红线，IotTelemetryPipelineIT :134-150 逐字同型）。
 *
 * <p><b>PR-4C Task 6（W-40）适配</b>：board REST 端点挂病区守卫（fail-closed）——admin 登录态
 * 用例补当班绑定行夹具（V1114 种子只覆盖 W01，本 IT 锚 W-IT-9006）；另增哨兵令牌 REST 场景
 * （@Order(4)：区内 200+越区 403——W-68 闭合与 A-2 HTTP 面锚定，写面 403 归 AuthFlowIT 第 10 步）。
 *
 * <p><b>PR-4C Task 7（A-2 WS SUBSCRIBE 病区防线）适配</b>：@Order(5) 增哨兵令牌 WS 全链——
 * 匿名令牌 CONNECT（携主体缓存）后区内订阅可达收帧、越区订阅 ERROR 帧拒绝且连接被服务端关闭
 * （e2e 前置 IT 锚；登录态绑定集分支归单测承载，@Order(2) 既有绑定行即区内放行旁证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NurseBoardWsIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（大屏快照 5s TTL 缓存载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（admitted 消费链与 tick 延迟档位载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 大屏锚定病区 */
    private static final String WARD_ID = "W-IT-9006";

    /** 床位墙夹具行（投影直插——board 读面夹具） */
    private static final long PATIENT_ID = 920601L;

    private static final String VISIT_ID = "I2026100300010";

    /** 床位墙夹具床号 */
    private static final String BED_NO = "IT9-61";

    /** 逾期清单夹具任务号（overdue_flag=true 直插——REST 段断言锚） */
    private static final String OVERDUE_TASK_NO = "TK-IT-BOARD-0001";

    /** tick 驱动任务号（overdue_flag=false+越阈值——tick 段断言锚） */
    private static final String TICK_TASK_NO = "TK-IT-BOARD-0002";

    /** 直插主键常量（MP ASSIGN_ID 应用层雪花，jdbcTemplate 直插须自带；雪花段外种子保留段） */
    private static final long PROJECTION_ROW_ID = 920611L;

    private static final long OVERDUE_TASK_ROW_ID = 920612L;

    private static final long TICK_TASK_ROW_ID = 920613L;

    /** W-40 当班绑定行夹具主键（V1114 种子段外保留段——雪花 19 位量级永不冲突） */
    private static final long ADMIN_BINDING_ROW_ID = 9114000000000000101L;

    /** WS 推送触发夹具就诊（admitted 信封注入锚——@Order(2) 首投与 @Order(5) 幂等重放共用） */
    private static final long PUSH_PATIENT_ID = 920602L;

    private static final String PUSH_VISIT_ID = "I2026100300011";

    /** STOMP 握手与连接建立的等待上限（秒） */
    private static final int STOMP_CONNECT_TIMEOUT_SECONDS = 30;

    /** BED_PATIENT 帧等待上限（MQ 消费→投影→推送链路收敛） */
    private static final Duration PUSH_TIMEOUT = Duration.ofSeconds(15);

    /** tick 帧等待上限（延迟档位 60s TTL 自然到期+扫描——覆盖完整档位周期的富余窗） */
    private static final Duration TICK_TIMEOUT = Duration.ofSeconds(150);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 嵌入式 Servlet 容器随机端口：STOMP WebSocket 握手目标 */
    @LocalServerPort
    private int localServerPort;

    /** MQ 发送模板：admitted 信封注入通道 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 信封编解码器：手工合成 admitted 上游帧 */
    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /** 用例级 STOMP 客户端调度器登记表：统一 destroy 回收（EX-36 范式，IotTelemetryPipelineIT 同款） */
    private final List<ThreadPoolTaskScheduler> stompClientSchedulers = new CopyOnWriteArrayList<>();

    /** 带 Bearer 的 GET 助手。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

    /** 无体响应解析兜底（NursingVitalSignFlowIT 同型收口）。 */
    private JsonNode toNode(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 轮询等待业务条件成立（tick/推送异步链路收敛）。 */
    private void awaitUntil(String description, long timeoutMillis, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /**
     * 用例级资源回收（EX-36）：销毁本用例内创建的全部 STOMP 客户端调度器（IotTelemetryPipelineIT
     * destroyStompClientSchedulers 同款）。
     */
    @AfterEach
    void destroyStompClientSchedulers() {
        for (ThreadPoolTaskScheduler scheduler : stompClientSchedulers) {
            // destroy 幂等（内部 shutdown 承载），断言失败路径同样经本收口回收
            scheduler.destroy();
        }
        stompClientSchedulers.clear();
    }

    /**
     * 建立 STOMP 会话：CONNECT 帧头携 Bearer 登录令牌（帧级鉴权与生产客户端同通道，
     * IotTelemetryPipelineIT connectStompSession 同款）。
     *
     * @param accessToken access 令牌原文，非空；来源：基类 loginToken
     * @return 已完成 CONNECT 的会话，非空
     * @throws Exception 连接超时或被拒绝（正路径不应发生）
     */
    private StompSession connectStompSession(String accessToken) throws Exception {
        return connectStompSession(accessToken, new StompSessionHandlerAdapter() {});
    }

    /**
     * 建立 STOMP 会话（自定义会话处理器版）：越区拒绝用例经定制 {@link StompSessionHandlerAdapter}
     * 捕获服务端 ERROR 帧（DefaultStompSession 对 ERROR 帧调用会话处理器 handleFrame——
     * spring-messaging 6.2.19 字节码同源）。
     *
     * @param accessToken access 令牌原文，非空；来源：登录或哨兵令牌签发
     * @param handler     STOMP 会话处理器，非空
     * @return 已完成 CONNECT 的会话，非空
     * @throws Exception 连接超时或被拒绝（正路径不应发生）
     */
    private StompSession connectStompSession(String accessToken, StompSessionHandlerAdapter handler) throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("it-nurse-board-sched-");
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        // 登记 + 用例末统一 destroy（EX-36：断连/被拒两类路径的调度线程均不驻留）
        stompClientSchedulers.add(taskScheduler);
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[] {0, 0});
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                "ws://localhost:" + localServerPort + "/ws/nursing",
                new WebSocketHttpHeaders(),
                connectHeaders,
                handler);
        try {
            return future.get(STOMP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // 连接被拒（帧级鉴权未过）以原 cause 暴露，失败语义不吞
            throw (Exception) (e.getCause() == null ? e : e.getCause());
        }
    }

    /**
     * 订阅大屏主题并返回帧队列（订阅写入后 500ms 稳定窗——IotTelemetryPipelineIT
     * subscribeForFrames 同款顺序保证口径）。
     */
    private BlockingQueue<String> subscribeForFrames(StompSession session, String destination)
            throws InterruptedException {
        BlockingQueue<String> frames = new ArrayBlockingQueue<>(32);
        StompHeaders subscribeHeaders = new StompHeaders();
        subscribeHeaders.setDestination(destination);
        session.subscribe(subscribeHeaders, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        Thread.sleep(500);
        return frames;
    }

    /** 注入 admitted 上游帧（nursing 消费→投影 upsert→BED_PATIENT 推送全链触发面；eventId 固定首投）。 */
    private void publishAdmitted(String visitId, long patientId) {
        publishAdmitted("it-board-admitted", visitId, patientId);
    }

    /**
     * 注入 admitted 上游帧（自定义 eventId 版）：消费侧按 eventId+模块幂等去重，同 visitId 重放
     * 须换 eventId（@Order(5) 哨兵场景重放 @Order(2) 夹具就诊——走投影幂等刷新路径，避免
     * uk_ward_patient_bed 空占位床号唯一冲突）。
     *
     * @param eventId   事件去重标识，非空（同一 JVM 内每次注入须互异）
     * @param visitId   夹具就诊号，非空
     * @param patientId 夹具患者 ID
     */
    private void publishAdmitted(String eventId, String visitId, long patientId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("visitId", visitId)
                .put("patientId", patientId)
                .put("wardId", WARD_ID)
                .put("bedId", 920621L)
                .put("admittedAt", "2026-10-03T02:00:00Z")
                .put("nursingLevel", "NORMAL");
        rabbitTemplate.convertAndSend(
                "fy.topic",
                "inpatient.visit.admitted",
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "inpatient",
                        "inpatient.visit.admitted",
                        eventId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    @Test
    @Order(1)
    @DisplayName("夹具与大屏 REST 快照四段：床位墙行/逾期清单段/出入院 ADMIT 动态/危急值空段占位")
    void boardRestSnapshotFourSections() {
        // W-40 守卫适配（D-21 申报）：admin 登录态调 board 须有当班绑定行（V1114 种子只覆盖 W01，
        // 本 IT 锚 W-IT-9006——照 Task 5 种子行形态直插，长期有效窗当日命中）
        jdbcTemplate.update("""
                INSERT INTO nursing.nurse_assignment
                  (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
                   valid_from, valid_to, status, created_by, updated_by, deleted)
                VALUES (?, 'W-IT-9006', '1', 'PRIMARY', 'DAY', NULL, NULL,
                   DATE '2026-01-01', NULL, 'ACTIVE', 'IT', 'IT', 0)
                """, ADMIN_BINDING_ROW_ID);
        jdbcTemplate.update(
                "INSERT INTO nursing.nursing_ward_patient"
                        + " (id, ward_id, bed_no, patient_id, visit_id, patient_name, nursing_level, allergy_flag,"
                        + " risk_flags, admitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, '', 'CRITICAL', false, '跌倒风险', now() - interval '2 hours')",
                PROJECTION_ROW_ID,
                WARD_ID,
                BED_NO,
                PATIENT_ID,
                VISIT_ID);
        jdbcTemplate.update(
                "INSERT INTO nursing.nursing_task"
                        + " (id, task_no, patient_id, visit_id, ward_id, task_type, source, plan_time, status,"
                        + " overdue_flag, escalation_count)"
                        + " VALUES (?, ?, ?, ?, ?, 'PATROL', 'MANUAL', now() - interval '50 minutes', 'PENDING',"
                        + " true, 1)",
                OVERDUE_TASK_ROW_ID,
                OVERDUE_TASK_NO,
                PATIENT_ID,
                VISIT_ID,
                WARD_ID);
        String token = loginToken(ADMIN_LOGIN_NAME);
        JsonNode board = getJson("/api/v1/nursing/board/" + WARD_ID, token);
        assertThat(board.path("wardId").asText()).as("快照病区锚").isEqualTo(WARD_ID);
        assertThat(board.hasNonNull("generatedAt")).as("快照生成时点（北京钟面）").isTrue();
        // 段①：床位墙（投影行七组件镜像）
        JsonNode beds = board.path("beds");
        assertThat(beds.isArray()).isTrue();
        JsonNode bedRow = beds.isEmpty() ? MissingNode.getInstance() : beds.get(0);
        assertThat(bedRow.path("bedNo").asText()).isEqualTo(BED_NO);
        assertThat(bedRow.path("visitId").asText()).isEqualTo(VISIT_ID);
        assertThat(bedRow.path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(bedRow.path("nursingLevel").asText()).isEqualTo("CRITICAL");
        assertThat(bedRow.path("riskFlags").asText()).as("风险标记镜像").isEqualTo("跌倒风险");
        // 段②：逾期清单（overdue_flag=true 且 PENDING）
        assertThat(containsTaskNo(board.path("overdueTasks"), OVERDUE_TASK_NO))
                .as("逾期清单段应含夹具任务")
                .isTrue();
        // 段③：出入院动态（近 24h ADMIT 行）
        JsonNode admissions = board.path("admissions");
        assertThat(admissions.isArray()).isTrue();
        boolean admitHit = false;
        for (JsonNode row : admissions) {
            admitHit = admitHit
                    || (VISIT_ID.equals(row.path("visitId").asText())
                            && "ADMIT".equals(row.path("type").asText()));
        }
        assertThat(admitHit).as("出入院动态应含 ADMIT 行").isTrue();
        // 段④：危急值空段占位（M07 缺位降级明示）
        assertThat(board.path("criticalValues")).as("危急值段恒空（M07 缺位占位）").isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("WS 主通道：订阅 /topic/nursing/board/{wardId} 后 admitted 事件注入，收到 BED_PATIENT 帧")
    void boardTopicPushesBedPatientFrame() throws Exception {
        String token = loginToken(ADMIN_LOGIN_NAME);
        StompSession session = connectStompSession(token);
        try {
            BlockingQueue<String> frames = subscribeForFrames(session, "/topic/nursing/board/" + WARD_ID);
            publishAdmitted(PUSH_VISIT_ID, PUSH_PATIENT_ID);
            String frame = frames.poll(PUSH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).as("床位动态帧应到达订阅会话").isNotNull();
            JsonNode envelope = objectMapper.readTree(frame);
            assertThat(envelope.path("type").asText()).as("帧类型=BED_PATIENT").isEqualTo("BED_PATIENT");
            assertThat(envelope.hasNonNull("occurredAt"))
                    .as("统一信封 occurredAt 在位")
                    .isTrue();
            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("visitId").asText()).isEqualTo(PUSH_VISIT_ID);
            assertThat(payload.path("patientId").asLong()).isEqualTo(PUSH_PATIENT_ID);
            assertThat(payload.path("wardId").asText()).as("载荷病区=路由病区").isEqualTo(WARD_ID);
        } finally {
            session.disconnect();
        }
        // 消费链落位旁证：投影行经 admitted 消费 upsert 在册
        awaitUntil("admitted 消费投影行落位", PUSH_TIMEOUT.toMillis(), () -> !jdbcTemplate
                .queryForList(
                        "SELECT * FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0", PUSH_VISIT_ID)
                .isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("tick 心跳观测：越阈值 PENDING 任务经延迟档位自然到期驱动首标+TASK_OVERDUE 推送")
    void tickDrivesOverdueMarkAndPushFrame() throws Exception {
        // tick 触发面：plan_time=now−40min（越首逾阈值 30 分钟）、overdue_flag=false（待首标）
        jdbcTemplate.update(
                "INSERT INTO nursing.nursing_task"
                        + " (id, task_no, patient_id, visit_id, ward_id, task_type, source, plan_time, status,"
                        + " overdue_flag, escalation_count)"
                        + " VALUES (?, ?, ?, ?, ?, 'IO_MONITOR', 'MANUAL', now() - interval '40 minutes',"
                        + " 'PENDING', false, 0)",
                TICK_TASK_ROW_ID,
                TICK_TASK_NO,
                PATIENT_ID,
                VISIT_ID,
                WARD_ID);
        String token = loginToken(ADMIN_LOGIN_NAME);
        StompSession session = connectStompSession(token);
        try {
            BlockingQueue<String> frames = subscribeForFrames(session, "/topic/nursing/board/" + WARD_ID);
            // 等待延迟档位自然到期（60s TTL 自续期——正常路径，勿造 DLX 场景口径）
            String frame = frames.poll(TICK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).as("tick 周期内应收到推送帧").isNotNull();
            JsonNode envelope = objectMapper.readTree(frame);
            assertThat(envelope.path("type").asText()).as("帧类型=TASK_OVERDUE").isEqualTo("TASK_OVERDUE");
            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("taskNo").asText()).as("载荷任务号=tick 触发面任务").isEqualTo(TICK_TASK_NO);
            assertThat(payload.path("wardId").asText()).as("载荷病区=路由病区").isEqualTo(WARD_ID);
            assertThat(payload.path("escalationCount").asInt())
                    .as("首标档位=1（责任护士档）")
                    .isEqualTo(1);
        } finally {
            session.disconnect();
        }
        // 库态终局：首标置位+升级计数 1（tick 扫描动作双锚）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT overdue_flag, escalation_count FROM nursing.nursing_task WHERE task_no = ?", TICK_TASK_NO);
        assertThat(row.get("overdue_flag")).as("tick 首标置位").isEqualTo(Boolean.TRUE);
        assertThat(((Number) row.get("escalation_count")).intValue())
                .as("首标档位 1")
                .isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("哨兵令牌 REST 面：区内 board 快照 200+越区 403（W-68 闭合主断言+A-2 HTTP 面一致性）")
    void sentinelTokenReadsBoardWithinBoundWardOnly() {
        // 匿名签发哨兵令牌（AUTH_WHITELIST 通道，携 wardId 绑定——W-39 签发面）
        String sentinelToken = issueSentinelToken(WARD_ID);
        // 区内：board 快照 200（W-68 闭合主断言——哨兵令牌附调后匿名大屏恢复；守卫内哨兵豁免直通）
        assertThat(bearerJson("/api/v1/nursing/board/" + WARD_ID, HttpMethod.GET, sentinelToken, null)
                        .getStatusCode()
                        .value())
                .as("区内 board 快照应 200")
                .isEqualTo(200);
        // 越区：路径尾段 != 令牌病区 → 403（A-2 HTTP 面——拦截器层限行拒绝，不达 controller）
        assertThat(bearerJson("/api/v1/nursing/board/W01", HttpMethod.GET, sentinelToken, null)
                        .getStatusCode()
                        .value())
                .as("越区 board 请求应 403")
                .isEqualTo(403);
    }

    /**
     * 匿名签发哨兵令牌（AUTH_WHITELIST 通道，W-39 签发面——REST @Order(4) 与 WS @Order(5) 共用）。
     *
     * @param wardId 令牌绑定病区编码，非空（尾段比对源——REST 一致性校验与 WS 订阅防线同源）
     * @return 哨兵 access 令牌原文，非空（禁入日志与断言消息）
     */
    private String issueSentinelToken(String wardId) {
        HttpHeaders issueHeaders = new HttpHeaders();
        issueHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> tokenResp = restTemplate.exchange(
                "/api/v1/system/auth/bigscreen-token?wardId=" + wardId,
                HttpMethod.POST,
                new HttpEntity<>(null, issueHeaders),
                String.class);
        assertThat(tokenResp.getStatusCode().value()).as("哨兵令牌签发应 200").isEqualTo(200);
        return JsonPath.<String>read(tokenResp.getBody(), "$.accessToken");
    }

    /**
     * 哨兵令牌 WS 面（PR-4C Task 7 A-2 e2e 锚）：匿名令牌经 CONNECT 帧鉴权（主体缓存）后，
     * 区内订阅真实建立（BED_PATIENT 帧可达为订阅落位的最小可靠证据）；越区订阅被
     * NursingSubscribeWardInterceptor 拒——客户端收 ERROR 帧（message=固定摘要）且服务端以
     * PROTOCOL_ERROR 关闭连接（spring-websocket 6.2.19 字节码同源：ERROR 帧先发、连接随后关闭，
     * DefaultStompSession 对 ERROR 帧调用会话处理器 handleFrame——经定制 handler 捕获）。
     */
    @Test
    @Order(5)
    @DisplayName("哨兵令牌 WS 面：区内订阅可达收帧+越区订阅 ERROR 帧拒绝且连接被服务端关闭（A-2 e2e 锚）")
    void sentinelWsSubscribeRestrictedToBoundWard() throws Exception {
        String sentinelToken = issueSentinelToken(WARD_ID);

        // ① 区内：CONNECT 成功 + 订阅真实落位（帧可达——被拒订阅无帧，收帧即防线放行的行为证据）；
        // 重放 @Order(2) 夹具就诊（幂等刷新路径）——换 eventId 避消费侧去重，同 ward 二次 admitted
        // 新插行会撞 uk_ward_patient_bed 空占位床号唯一索引（床号待 bed.changed 补齐口径）
        StompSession inWard = connectStompSession(sentinelToken);
        try {
            BlockingQueue<String> frames = subscribeForFrames(inWard, "/topic/nursing/board/" + WARD_ID);
            publishAdmitted("it-board-admitted-sentinel", PUSH_VISIT_ID, PUSH_PATIENT_ID);
            String frame = frames.poll(PUSH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(frame).as("哨兵区内订阅应可收床位动态帧").isNotNull();
            assertThat(objectMapper.readTree(frame).path("type").asText())
                    .as("帧类型=BED_PATIENT（订阅落位旁证）")
                    .isEqualTo("BED_PATIENT");
        } finally {
            inWard.disconnect();
        }

        // ② 越区：尾段 != 令牌绑定病区 → ERROR 帧（固定摘要，不含令牌）+ 连接被服务端关闭
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();
        StompSession outWard = connectStompSession(sentinelToken, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // ERROR 帧抵达即服务端拒绝证据（订阅级帧不会到达——连接关闭前仅此一帧）；
                // message 头承载服务端固定摘要（StompHeaders 无 getMessage 便捷器，经 getFirst 取原生头）
                errorMessage.set(headers.getFirst("message"));
                errorLatch.countDown();
            }
        });
        try {
            // 越层订阅 W01（V303 种子病区，非令牌绑定 W-IT-9006）：订阅处理器不会被调用，占位即可
            StompHeaders subscribeHeaders = new StompHeaders();
            subscribeHeaders.setDestination("/topic/nursing/board/W01");
            outWard.subscribe(subscribeHeaders, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    // 被拒订阅无帧回调（占位实现，断言语义在会话级 ERROR 帧）
                }
            });
            assertThat(errorLatch.await(PUSH_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("越区订阅应收到服务端 ERROR 帧")
                    .isTrue();
            assertThat(errorMessage.get())
                    .as("ERROR 帧消息=防线固定摘要（不含令牌与绑定差异——防枚举）")
                    .isEqualTo("大屏匿名令牌仅可访问绑定病区的看板主题");
            // 服务端发 ERROR 后关闭连接（PROTOCOL_ERROR）——会话终将被置为非连接态
            awaitUntil("越区被拒后连接应被服务端关闭", PUSH_TIMEOUT.toMillis(), () -> !outWard.isConnected());
        } finally {
            // 连接已被服务端以 PROTOCOL_ERROR 关闭（DefaultStompSession 对已关闭会话 disconnect
            // 抛 IllegalStateException——isConnected 守卫跳过，断言失败路径同样不因清理噪音遮蔽）
            if (outWard.isConnected()) {
                outWard.disconnect();
            }
        }
    }

    /**
     * 携 Bearer 令牌的原始响应请求助手（AuthFlowIT bearerJson 同款——哨兵 REST 场景状态码断言通道）。
     *
     * @param path     目标 URI（/api/v1 前缀），非空
     * @param method   HTTP 方法，非空
     * @param token    Bearer 令牌原文，非空；经 Authorization 头注入（禁入日志）
     * @param jsonBody 请求体 JSON 字符串，可空；GET 传 null
     * @return 原始 HTTP 响应（状态码可断言），非空
     */
    private ResponseEntity<String> bearerJson(String path, HttpMethod method, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /** 大屏快照逾期清单段包含判定（taskNo 维度——清单行组件化断言承载）。 */
    private static boolean containsTaskNo(JsonNode section, String taskNo) {
        for (JsonNode row : section) {
            if (taskNo.equals(row.path("taskNo").asText())) {
                return true;
            }
        }
        return false;
    }
}

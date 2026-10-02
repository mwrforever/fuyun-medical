package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelopeCodec;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

    /** WS 推送触发夹具就诊（admitted 信封注入锚） */
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
                new StompSessionHandlerAdapter() {});
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

    /** 注入 admitted 上游帧（nursing 消费→投影 upsert→BED_PATIENT 推送全链触发面）。 */
    private void publishAdmitted(String visitId, long patientId) {
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
                        "it-board-admitted",
                        objectMapper.convertValue(payload, Map.class)));
    }

    @Test
    @Order(1)
    @DisplayName("夹具与大屏 REST 快照四段：床位墙行/逾期清单段/出入院 ADMIT 动态/危急值空段占位")
    void boardRestSnapshotFourSections() {
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

package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-5 验收锚点③：放号瞬间并发抢号零超卖真栈（HTTP/Redis/DB 零 mock）——
 * 号源池 total_quota=5、20 并发窗口挂号：双道闸（Redis Lua 预扣第一道 + 池行 version 乐观锁
 * 条件更新第二道）收敛为恰 5 成功、used_count=5、Redis 余量键归零、败者 409 限流码、预约表恰
 * 5 行；同患者同日同科第二单 409 OP-1005 限购拦截且池行零变化。全链快速失败边界
 * （03 Spec :209）以并发段 wall time &lt;30s 断言承载。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutpatientPoolConcurrencyIT extends FuyunStackITBase {

    /** 类级独占三容器（容器禁收敛入基类——P1-1 裁决，BillingSettlementFlowIT :62-78 逐字同型；
     *  @DynamicPropertySource 密钥三元组已由 FuyunStackITBase 承载，本类不重复声明） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（号源池键 fy:outpatient:pool:{poolId} 双道闸第一道 + 单号流水键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（挂号链 visit.registered 真实发布的承接面；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 号源池总量冻结值（Spec 验收口径：20 抢 5 零超卖） */
    private static final long POOL_TOTAL_QUOTA = 5L;

    /** 并发抢号线程数（放号瞬间挤兑面） */
    private static final int CONCURRENT_THREADS = 20;

    /** 并发抢号快速失败边界（03 Spec :209：全链 wall time <30s） */
    private static final long CONCURRENCY_WALL_TIME_LIMIT_SECONDS = 30L;

    /** 本 IT 专属开诊科室（与容器内其他造数隔离，快照/限购谓词维度） */
    private static final String DEPT_CODE = "DEP-IT-CONC";

    /** 排班模板造数行主键（schedule_template SQL 直插——brief 造数口径） */
    private static final long TEMPLATE_ID = 910401L;

    /** 并发抢号患者主索引起点（patient.patient SQL 直插 20 人，resolve 仅需主档行） */
    private static final long FIRST_PATIENT_ID = 910501L;

    /** 并发抢号患者数（每人至多一单，限购谓词互不干扰） */
    private static final int PATIENT_COUNT = 20;

    /** V303 种子超管 id（booking 操作者留痕口径） */
    private static final long ADMIN_USER_ID = 1L;

    private static String adminToken = "";

    /** 跨用例链路状态（JUnit 每用例新实例，池行 id 经 static 传递） */
    private static long poolId;

    /** 并发抢号成功的患者 id 集（同患者重复挂号用例取首个成功者） */
    private static final List<Long> SUCCESS_PATIENT_IDS = new ArrayList<>();

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 事件治理占位（A.5-4：禁止测试自声明交换机/裸队列）：本 IT 零注入帧、断言锚是响应
     * errorCode 与 DB/Redis 终态；挂号 WINDOW 链真实发布的 visit.registered 经本队列承接，
     * 防无绑定帧 mandatory 退回刷屏（PharmacyDispenseGuardIT ItGovernanceConfig 同型）。
     */
    @TestConfiguration
    static class ItGovernanceConfig {

        @Bean
        Declarables itVisitRegisteredQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", OutpatientMessagingConstants.EVENT_VISIT_REGISTERED));
        }
    }

    /** 带 Bearer 的 GET 助手（BillingSettlementFlowIT :173-180 同型）。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

    private JsonNode toNode(String body) {
        // 无体响应（void/204 端点）回 MISSING 单例，防 readTree 拒 null 中断用例（既有 IT 同型收口）
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** POST JSON 助手（带 Content-Type/Bearer；状态码断言场景改用 postForEntity）。 */
    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 带令牌 POST（返回原始响应实体，状态码与体 errorCode 断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 号源池键拼装（fy:outpatient:pool:{poolId}，A.5-1；{poolId} 为 hash tag 字面量） */
    private static String poolKey(long poolId) {
        return "fy:outpatient:pool:{" + poolId + "}";
    }

    /** 读池行已用号数（DB 权威库存，双道闸第二道终态断言锚）。 */
    private long poolUsedCount() {
        Long used = jdbcTemplate.queryForObject(
                "SELECT used_count FROM outpatient.appt_number_pool WHERE id = ?", Long.class, poolId);
        return used == null ? -1 : used;
    }

    /** 读该池行预约单行数（放号抢号终态=池行占用数与单据数勾稽）。 */
    private long appointmentCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outpatient.appointment WHERE pool_id = ? AND deleted = 0", Long.class, poolId);
        return n == null ? 0 : n;
    }

    /**
     * 对齐起跑并发执行（PharmacyDispenseGuardIT runConcurrent 范式扩展至 N 线程：CountDownLatch
     * 保证抢号请求尽量同拍发出，命中放号瞬间并发窗口）。
     *
     * @param actions 各线程执行的 HTTP 动作（线程序即列表序）
     * @return 各线程响应（列表序与 actions 一一对应）
     * @throws Exception 线程池等待中断/超时（测试基础设施故障显式失败）
     */
    private List<ResponseEntity<String>> runConcurrent(
            List<java.util.function.Supplier<ResponseEntity<String>>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (java.util.function.Supplier<ResponseEntity<String>> action : actions) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            List<ResponseEntity<String>> results = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            long wallMillis = (System.nanoTime() - begin) / 1_000_000L;
            // 快速失败边界（Spec :209）：放号挤兑全链（含全部 409 快拒）须在 30s 内收敛
            assertThat(wallMillis)
                    .as("并发抢号全链 wall time %dms 应 <30s（快速失败边界）", wallMillis)
                    .isLessThan(CONCURRENCY_WALL_TIME_LIMIT_SECONDS * 1000L);
            return results;
        } finally {
            // 线程池回收移入 finally：wall time 断言失败或等待中断时亦回收，防线程泄漏
            executor.shutdownNow();
        }
    }

    @Test
    @Order(1)
    @DisplayName("前置：登录；排班模板直插（quota=5 全周）→ T+N 放号生成当日池行+池键预热；20 患者 SQL 直插建档")
    void preparePoolAndPatients() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);

        // 排班模板 SQL 直插（号源配置属运行时配置面，brief 造数口径；slot_quota=并发抢号总量）
        jdbcTemplate.update(
                "INSERT INTO outpatient.schedule_template (id, dept_code, doctor_id, eff_from, eff_to,"
                        + " week_pattern, session, appt_type, slot_start, slot_end, slot_quota, room,"
                        + " release_days, release_time, status)"
                        + " VALUES (?, ?, '3', CURRENT_DATE, NULL, '1111111', 'MORNING', 'GENERAL',"
                        + " TIME '08:00', TIME '12:00', ?, 'IT-ROOM-01', 1, TIME '07:00', 'ACTIVE')",
                TEMPLATE_ID,
                DEPT_CODE,
                POOL_TOTAL_QUOTA);

        // T+N 放号（真实生成链：排班+池行落库+Redis 池键 prime 预热——双道闸第一道自本调用生效）
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("endDate", LocalDate.now().plusDays(1).toString()).put("days", 2);
        int generated = postJson("/api/v1/outpatient/schedules/generate", adminToken, generate)
                .asInt();
        assertThat(generated).as("两日窗口×单模板应生成两行排班").isEqualTo(2);

        // 定位当日池行（生成循环按日期升序展开，同日唯一模板唯一池行）
        Long located = jdbcTemplate.queryForObject(
                "SELECT p.id FROM outpatient.appt_number_pool p"
                        + " JOIN outpatient.schedule s ON s.id = p.schedule_id"
                        + " WHERE s.sched_date = ? AND p.deleted = 0 AND s.deleted = 0"
                        + " ORDER BY p.id LIMIT 1",
                Long.class,
                LocalDate.now());
        assertThat(located).as("当日号源池行应在位").isNotNull();
        poolId = located;
        Long total = jdbcTemplate.queryForObject(
                "SELECT total_quota FROM outpatient.appt_number_pool WHERE id = ?", Long.class, poolId);
        assertThat(total).as("冻结验收口径：池总量=5").isEqualTo(POOL_TOTAL_QUOTA);
        // 池键预热实证：余量键在位且等于总量（双道闸第一道可用前提）
        assertThat(redisTemplate.opsForValue().get(poolKey(poolId)))
                .as("放号 prime 后池键余量应等于总量")
                .isEqualTo(String.valueOf(POOL_TOTAL_QUOTA));

        // 20 患者 SQL 直插建档（patient.resolve 仅需主档行 NORMAL——brief 造数口径「SQL 直插或建档 API」）
        for (int i = 0; i < PATIENT_COUNT; i++) {
            jdbcTemplate.update(
                    "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                            + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                    FIRST_PATIENT_ID + i,
                    "并发抢号患者" + i);
        }
        Long patientRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM patient.patient WHERE patient_id >= ? AND patient_id < ?",
                Long.class,
                FIRST_PATIENT_ID,
                FIRST_PATIENT_ID + PATIENT_COUNT);
        assertThat(patientRows).as("20 名并发患者建档应在位").isEqualTo((long) PATIENT_COUNT);
    }

    @Test
    @Order(2)
    @DisplayName("20 并发抢 5 号零超卖：恰 5 单成功+used_count=5+Redis 余量键=0+败者 409（OP-1003/OP-1005）+单据恰 5 行")
    void concurrentBookingNeverOversellsPool() throws Exception {
        List<java.util.function.Supplier<ResponseEntity<String>>> actions = new ArrayList<>();
        for (int i = 0; i < PATIENT_COUNT; i++) {
            long patientId = FIRST_PATIENT_ID + i;
            actions.add(() -> {
                ObjectNode req = objectMapper.createObjectNode();
                req.put("patientId", patientId).put("poolId", poolId).put("channel", "WINDOW");
                return postForEntity("/api/v1/outpatient/appointments", adminToken, req);
            });
        }
        List<ResponseEntity<String>> results = runConcurrent(actions);

        // 成功面：恰 5 单 2xx——直出 AppointmentVO 携 TAKEN 态与 visitId（同事务签发就诊）
        List<Long> successPatients = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            ResponseEntity<String> resp = results.get(i);
            if (resp.getStatusCode().is2xxSuccessful()) {
                JsonNode vo = toNode(resp.getBody());
                assertThat(vo.path("status").asText())
                        .as("窗口挂号应一步直达 TAKEN：patientId=%s", FIRST_PATIENT_ID + i)
                        .isEqualTo("TAKEN");
                assertThat(vo.path("visitId").asText()).as("成功单应同事务签发 CF-3 就诊号").matches("O\\d{13}");
                successPatients.add(FIRST_PATIENT_ID + i);
            }
        }
        assertThat(successPatients).as("20 并发抢 5 号：恰 5 单成功").hasSize((int) POOL_TOTAL_QUOTA);
        SUCCESS_PATIENT_IDS.addAll(successPatients);

        // 失败面：15 败者全部 409 且业务码限于号源不足（OP-1003）/限购冲突（OP-1005）两词表值
        for (int i = 0; i < results.size(); i++) {
            ResponseEntity<String> resp = results.get(i);
            if (resp.getStatusCode().is2xxSuccessful()) {
                continue;
            }
            assertThat(resp.getStatusCode().value())
                    .as("败者应 409：patientId=%s", FIRST_PATIENT_ID + i)
                    .isEqualTo(409);
            assertThat(toNode(resp.getBody()).path("errorCode").asText())
                    .as("败者业务码应为号源不足/限购：patientId=%s", FIRST_PATIENT_ID + i)
                    .isIn("OP-1003", "OP-1005");
        }
        long failedCount = results.stream()
                .filter(r -> !r.getStatusCode().is2xxSuccessful())
                .count();
        assertThat(failedCount).as("恰 15 单被拒").isEqualTo(PATIENT_COUNT - POOL_TOTAL_QUOTA);

        // 终态勾稽：DB 权威库存恰 5、预约单据恰 5 行、Redis 余量键归零（双道闸两面+快路径三方一致）
        assertThat(poolUsedCount()).as("池行 used_count 应恰为 5（零超卖）").isEqualTo(POOL_TOTAL_QUOTA);
        assertThat(appointmentCount()).as("该池预约单据应恰 5 行").isEqualTo(POOL_TOTAL_QUOTA);
        assertThat(redisTemplate.opsForValue().get(poolKey(poolId)))
                .as("Redis 余量键应扣减归零")
                .isEqualTo("0");
        // 就诊签发勾稽：每张成功单同事务签发一条 visit（visit_id 非空行数=5）
        Long visitRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outpatient.visit WHERE appt_id IN"
                        + " (SELECT id FROM outpatient.appointment WHERE pool_id = ? AND deleted = 0)",
                Long.class,
                poolId);
        assertThat(visitRows).as("成功单应同事务签发就诊记录恰 5 条").isEqualTo(POOL_TOTAL_QUOTA);
    }

    @Test
    @Order(3)
    @DisplayName("同患者同日同科第二单：409 OP-1005 限购拦截且池行 used_count 不变")
    void duplicateBookingSameDaySameDeptRejected() {
        long patientId = SUCCESS_PATIENT_IDS.get(0);
        long usedBefore = poolUsedCount();

        ObjectNode req = objectMapper.createObjectNode();
        req.put("patientId", patientId).put("poolId", poolId).put("channel", "WINDOW");
        ResponseEntity<String> resp = postForEntity("/api/v1/outpatient/appointments", adminToken, req);

        assertThat(resp.getStatusCode().value()).as("同患者同日同科第二单应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText())
                .as("限购拦截业务码应 OP-1005")
                .isEqualTo("OP-1005");
        assertThat(poolUsedCount()).as("限购前置即拒：池行占用零变化").isEqualTo(usedBefore);
        assertThat(appointmentCount()).as("限购前置即拒：预约单据零新增").isEqualTo(POOL_TOTAL_QUOTA);
    }
}

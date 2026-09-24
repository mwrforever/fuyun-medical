package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
 * PR-6 M05 验收锚点①：体征归集链真栈 IT（FU-M05-02/03，零 mock HTTP/DB/MQ）。链路：入区登记
 * （真实流转经 POST /ward-patients 落视图行、GET 一览回读命中）→ 体征录入（全项正常合并当日
 * 观察行 / 二次正常换行追加 / 异常独立落行）→ 体温单 VITAL 条目逐次写入 → 同刻同部位重复入
 * 权威栏幂等拒绝（NS-1016，事务回滚行数不变）→ 生理极限拒收（NS-1005）→ 待复核夹具行复核
 * 转正（补写体温单条目 + 二次转正 NS-1015）→ 事件帧断言（nursing.vital-sign.recorded 经
 * 捕获队列消费，异常帧与转正帧按载荷锚定）。
 *
 * <p>偏差登记（简报 vs 实况，详见 task-11-report）：①简报 step5 设想「同 (visit_id,
 * measured_at, temp_site) 再 POST 被拒 NS-1019」——实况 measured_at 为服务端时间（GC25），
 * 顺序 HTTP 再录必然异刻，vital 层唯一索引经 API 不可达（DuplicateKey→NS-1016 翻译已有
 * VitalSignServiceImplTest 单测锚）；本 IT 改以同语义验证：重开待复核行再 confirm 重入权威栏，
 * 撞体温单 (page, entry_time, VITAL, type_key) 唯一键 → 409 NS-1016、行数不变、事务回滚。
 * ②简报 step1「返回 status=IN_WARD」——WardPatientVO（GC39 冻结面）无 status 组件，改以
 * 2xx + jdbcTemplate 行 status + GET 一览命中断言。③患者主档行经 jdbcTemplate 直插
 * （resolve 依赖，OutpatientFullFlowIT:422 同款主数据夹具，非视图行验收）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NursingVitalSignFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（GC9 红线：容器禁收敛入基类——各 IT 独占一套，防捕获队列串扰） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（护理业务号发号器 NursingSeqGate 原子自增键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（nursing.vital-sign.recorded 真实发布链；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 体征链患者主索引（patient.patient SQL 直插主数据夹具；resolve 归一依赖） */
    private static final long PATIENT_ID = 920051L;

    /** 体征链住院就诊号（brief 冻结字面量：I 型 14 位） */
    private static final String VISIT_ID = "I2026092200001";

    /** 待复核体征夹具行 id（状态机边界夹具：PENDING_REVIEW 行经 jdbcTemplate 构造，批复口径允许项） */
    private static final long PENDING_FIXTURE_ID = 920601L;

    /** 跨用例链路状态（JUnit 每用例新实例，登录令牌与行锚经 static 传递） */
    private static String token = "";

    /** step4 异常体征行 id（step5 重开待复核再转正的碰撞载体） */
    private static long abnormalVitalId;

    /**
     * 捕获队列声明（A.5-4 治理红线：禁测试自声明交换机/裸队列——经 MessagingGovernance 声明；
     * OutpatientFullFlowIT:148-155 的 "it" 消费者模块形态）。一事件一队列：
     * q.it.nursing.vital-sign.recorded（V800 id 56 登记行）；声明副作用 registerSubscriber
     * 把 "it" 追加进订阅清单，与 billing/outpatient 先例同款。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_VITAL_RECORDED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED;

        /** 帧到达闩（本链确定性 4 帧：step2/3/4 录入 + step7 转正；step5 回滚与 step6 拒收零帧） */
        static final CountDownLatch LATCH = new CountDownLatch(3);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itVitalRecordedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED));
        }

        @Bean
        ItCaptureListener itCaptureListener(EventEnvelopeCodec codec) {
            return new ItCaptureListener(codec);
        }
    }

    /** 监听器本体（测试侧轻量：解析→按事件类型收帧置闩，不登记 received_event）。 */
    static class ItCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = {ItCaptureConfig.Q_VITAL_RECORDED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED.equals(envelope.eventType())) {
                ItCaptureConfig.CAPTURED.add(envelope);
                ItCaptureConfig.LATCH.countDown();
            }
        }
    }

    /** 带 Bearer 的 GET 助手（OutpatientFullFlowIT:187-194 同型）。 */
    private JsonNode getJson(String path, String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
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

    /** 带令牌 POST（返回原始响应实体，状态码与体 errorCode 断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String authToken, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(authToken);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** 无体 POST 助手（confirm 等动作端点的状态码断言面）。 */
    private ResponseEntity<String> postEmpty(String path, String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
    }

    /** 体征录入请求体构造（体温项 + 全常规指标；source=MANUAL）。 */
    private ObjectNode vitalBody(String temperature, String tempSite) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID)
                .put("source", "MANUAL")
                .put("temperature", temperature)
                .put("tempSite", tempSite)
                .put("pulse", 80)
                .put("respiration", 18)
                .put("systolicBp", 120)
                .put("diastolicBp", 80)
                .put("spo2", 98);
        return req;
    }

    /** 该就诊体征行总数（重复拒绝/拒收用例的行数不变断言锚）。 */
    private int vitalCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.vital_sign_record WHERE visit_id = ?", Integer.class, VISIT_ID);
        return count == null ? 0 : count;
    }

    /** 该就诊体温单 VITAL 条目总数（转正补写与重复入栏碰撞断言锚）。 */
    private int chartVitalCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry e"
                        + " JOIN nursing.temperature_chart_page p ON p.id = e.page_id"
                        + " WHERE p.visit_id = ? AND e.entry_type = 'VITAL'",
                Integer.class,
                VISIT_ID);
        return count == null ? 0 : count;
    }

    /** 该就诊自动归集观察行总数（合并/新建分支断言锚）。 */
    private int observationRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_record WHERE visit_id = ? AND auto_generated = true",
                Integer.class,
                VISIT_ID);
        return count == null ? 0 : count;
    }

    @Test
    @Order(1)
    @DisplayName("入区登记：POST /ward-patients 2xx 落 nursing_ward_patient 一行 IN_WARD，GET 一览回读命中（真实流转口径）")
    void step1_registerWardPatient() {
        token = loginToken(ADMIN_LOGIN_NAME);
        // 主数据夹具：患者主档行（resolve 归一依赖；OutpatientFullFlowIT 同款直插先例）
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 体征患者");
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID)
                .put("patientId", PATIENT_ID)
                .put("wardId", "W01")
                .put("bedNo", "01")
                .put("patientName", "IT 体征患者")
                .put("nursingLevel", "NORMAL");
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/ward-patients", token, req);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("入区登记应 2xx，实况：%s", resp.getBody())
                .isTrue();
        assertThat(toNode(resp.getBody()).path("visitId").asText()).isEqualTo(VISIT_ID);
        // 行落库断言（brief 明示的 jdbcTemplate 行锚；WardPatientVO 无 status 组件故取库态）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, patient_id, ward_id, bed_no FROM nursing.nursing_ward_patient WHERE visit_id = ?",
                VISIT_ID);
        assertThat(row.get("status")).as("视图行应为在区态").isEqualTo("IN_WARD");
        assertThat(((Number) row.get("patient_id")).longValue()).isEqualTo(PATIENT_ID);
        // 真实流转一览验收：GET /ward-patients 回读命中（禁以直插视图行冒充验收）
        JsonNode list = getJson("/api/v1/nursing/ward-patients?wardId=W01", token);
        assertThat(list.isArray()).isTrue();
        boolean hit = false;
        for (JsonNode item : list) {
            hit = hit || VISIT_ID.equals(item.path("visitId").asText());
        }
        assertThat(hit).as("在区一览应回读命中登记行").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("体征录入（全项正常）：录入即 CONFIRMED/abnormal=false，体温单 VITAL 条目一行，观察行恰一行含 36.5")
    void step2_recordNormalVitalSignsMergesObservation() {
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/vital-signs", token, vitalBody("36.5", "AXILLARY"));
        assertThat(resp.getStatusCode().value())
                .as("正常体征录入应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        assertThat(vo.path("reviewStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(vo.path("abnormalFlag").asBoolean()).isFalse();
        long vitalId = vo.path("id").asLong();
        // 库态复核：CONFIRMED 快照 + abnormal_flag=false
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT review_status, abnormal_flag FROM nursing.vital_sign_record WHERE id = ?", vitalId);
        assertThat(row.get("review_status")).isEqualTo("CONFIRMED");
        assertThat(row.get("abnormal_flag")).isEqualTo(Boolean.FALSE);
        // 体温单 VITAL 条目恰一行且引用该体征行
        Integer entries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry WHERE entry_type = 'VITAL' AND vital_ref = ?",
                Integer.class,
                vitalId);
        assertThat(entries).as("VITAL 条目应指向体征行").isEqualTo(1);
        assertThat(chartVitalCount()).isEqualTo(1);
        // 观察行归集：全项正常合并当日观察行，恰一行且含体温值
        assertThat(observationRowCount()).as("首笔正常体征应归集恰一行观察行").isEqualTo(1);
        String observation = jdbcTemplate.queryForObject(
                "SELECT observation FROM nursing.nursing_record WHERE visit_id = ? AND auto_generated = true",
                String.class,
                VISIT_ID);
        assertThat(observation).as("观察行应含首笔体温值").contains("36.5");
    }

    @Test
    @Order(3)
    @DisplayName("二次正常体征：观察行换行追加不新建（合并分支），observation 同时含 36.5 与 36.7")
    void step3_recordSecondNormalVitalSignsMergesSameRow() {
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/vital-signs", token, vitalBody("36.7", "AXILLARY"));
        assertThat(resp.getStatusCode().value()).as("二次正常录入应 200").isEqualTo(200);
        assertThat(observationRowCount()).as("合并分支不应新建观察行").isEqualTo(1);
        String observation = jdbcTemplate.queryForObject(
                "SELECT observation FROM nursing.nursing_record WHERE visit_id = ? AND auto_generated = true",
                String.class,
                VISIT_ID);
        assertThat(observation).as("观察行应保留首笔内容").contains("36.5");
        assertThat(observation).as("观察行应追加第二笔内容").contains("36.7");
        assertThat(chartVitalCount()).as("体温单条目随录随增").isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("异常体征（38.6）：观察行新建独立落行（abnormal 不被正常行稀释），含 38.6 与「高于正常范围」")
    void step4_recordAbnormalVitalSignsCreatesNewRow() {
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/vital-signs", token, vitalBody("38.6", "AXILLARY"));
        assertThat(resp.getStatusCode().value()).as("异常体征录入应 200").isEqualTo(200);
        abnormalVitalId = toNode(resp.getBody()).path("id").asLong();
        assertThat(observationRowCount()).as("异常项应独立新建观察行").isEqualTo(2);
        String abnormalObservation = jdbcTemplate.queryForObject(
                "SELECT observation FROM nursing.nursing_record WHERE visit_id = ? AND abnormal_flag = true",
                String.class,
                VISIT_ID);
        assertThat(abnormalObservation).as("异常行应含体温值").contains("38.6");
        assertThat(abnormalObservation)
                .as("异常行应含阈值结论文本（NursingVitalThresholds 冻结锚）")
                .contains("高于正常范围");
        assertThat(chartVitalCount()).as("体温单条目应新增至三条").isEqualTo(3);
    }

    @Test
    @Order(5)
    @DisplayName("同刻同部位重复入权威栏：重开待复核行再 confirm 撞体温单唯一键 409 NS-1016，行数不变（事务回滚）")
    void step5_duplicateVitalSignRejected() {
        int vitalBefore = vitalCount();
        int chartBefore = chartVitalCount();
        // 状态机边界夹具：已入卡行重开为待复核（待复核体征行构造，批复口径允许项）
        jdbcTemplate.update(
                "UPDATE nursing.vital_sign_record SET review_status = 'PENDING_REVIEW' WHERE id = ?", abnormalVitalId);
        // 转正重入权威栏：同 (visit, measured_at, 部位) 体征条目已存在 → 唯一键幂等拒绝（不覆盖首值）
        ResponseEntity<String> resp = postEmpty("/api/v1/nursing/vital-signs/" + abnormalVitalId + "/confirm", token);
        assertThat(resp.getStatusCode().value()).as("同刻同部位重复入卡应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1016");
        // 事务回滚实证：体征行数不变、体温单条目不变、行仍回滚在 PENDING_REVIEW（CAS 未提交）
        assertThat(vitalCount()).as("重复拒绝不得新增体征行").isEqualTo(vitalBefore);
        assertThat(chartVitalCount()).as("重复拒绝不得新增体温单条目").isEqualTo(chartBefore);
        String reviewStatus = jdbcTemplate.queryForObject(
                "SELECT review_status FROM nursing.vital_sign_record WHERE id = ?", String.class, abnormalVitalId);
        assertThat(reviewStatus).as("拒绝后事务回滚应留在待复核态").isEqualTo("PENDING_REVIEW");
    }

    @Test
    @Order(6)
    @DisplayName("生理极限拒收：体温 44.0 越极限 400 NS-1005，脏值不入库（行数不变）")
    void step6_outOfPhysiologicalLimitRejected() {
        int before = vitalCount();
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/vital-signs", token, vitalBody("44.0", "AXILLARY"));
        assertThat(resp.getStatusCode().value()).as("越生理极限应 400 拒收").isEqualTo(400);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1005");
        assertThat(vitalCount()).as("拒收脏值不得入库").isEqualTo(before);
    }

    @Test
    @Order(7)
    @DisplayName("复核转正：待复核夹具行命中工作台清单，confirm 200 行转 CONFIRMED 且补写体温单条目，二次转正 409 NS-1015")
    void step7_reviewFlowOnConstructedPendingRow() {
        // 状态机边界夹具：待复核体征行直插（P1 无生产写入方，IoT 归 P2），时点取 30 分钟前与录入行错开
        jdbcTemplate.update(
                "INSERT INTO nursing.vital_sign_record"
                        + " (id, visit_id, patient_id, ward_id, measured_at, temperature, temp_site, source,"
                        + " review_status, abnormal_flag, created_by, updated_by)"
                        + " VALUES (?, ?, ?, 'W01', now() - interval '30 minutes', 36.8, 'ORAL', 'MANUAL',"
                        + " 'PENDING_REVIEW', false, 'it-fixture', 'it-fixture')",
                PENDING_FIXTURE_ID,
                VISIT_ID,
                PATIENT_ID);
        // 待复核工作台清单命中（真实流转 GET 面）
        JsonNode pending = getJson("/api/v1/nursing/vital-signs/pending-review?wardId=W01", token);
        boolean hit = false;
        for (JsonNode item : pending) {
            hit = hit || item.path("id").asLong() == PENDING_FIXTURE_ID;
        }
        assertThat(hit).as("待复核清单应命中夹具行").isTrue();
        int chartBefore = chartVitalCount();
        // 转正：行转 CONFIRMED 且补写体温单 VITAL 条目（转正入权威栏）
        ResponseEntity<String> confirmed =
                postEmpty("/api/v1/nursing/vital-signs/" + PENDING_FIXTURE_ID + "/confirm", token);
        assertThat(confirmed.getStatusCode().value()).as("待复核行转正应 200").isEqualTo(200);
        assertThat(toNode(confirmed.getBody()).path("reviewStatus").asText()).isEqualTo("CONFIRMED");
        String reviewStatus = jdbcTemplate.queryForObject(
                "SELECT review_status FROM nursing.vital_sign_record WHERE id = ?", String.class, PENDING_FIXTURE_ID);
        assertThat(reviewStatus).as("转正后库态应为 CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(chartVitalCount()).as("转正应补写体温单条目（+1）").isEqualTo(chartBefore + 1);
        // 二次转正：CAS 0 行定性 NS-1015（仅待复核行可转正）
        ResponseEntity<String> again =
                postEmpty("/api/v1/nursing/vital-signs/" + PENDING_FIXTURE_ID + "/confirm", token);
        assertThat(again.getStatusCode().value()).as("已转正行重复转正应 409").isEqualTo(409);
        assertThat(toNode(again.getBody()).path("errorCode").asText()).isEqualTo("NS-1015");
    }

    @Test
    @Order(8)
    @DisplayName("事件发布：nursing.vital-sign.recorded 经捕获队列可消费，≥3 帧且异常帧与转正帧均在（载荷锚定）")
    void step8_eventPublished() throws Exception {
        assertThat(ItCaptureConfig.LATCH.await(10, TimeUnit.SECONDS))
                .as("vital-sign.recorded 事件应可消费（≤10s）")
                .isTrue();
        List<EventEnvelope> frames = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> NursingMessagingConstants.EVENT_VITAL_SIGN_RECORDED.equals(e.eventType()))
                .toList();
        assertThat(frames.size()).as("链路帧数应 ≥3（录入×3 + 转正×1）").isGreaterThanOrEqualTo(3);
        assertThat(frames.get(0).producer()).isEqualTo(NursingMessagingConstants.MODULE);
        // 异常帧：全链唯一 abnormal=true（step4 的 38.6 录入；step5 回滚与 step7 夹具均非异常）
        EventEnvelope abnormalFrame = frames.stream()
                .filter(e -> e.payload().path("abnormal").asBoolean())
                .findFirst()
                .orElseThrow();
        assertThat(abnormalFrame.payload().path("visitId").asText()).isEqualTo(VISIT_ID);
        assertThat(abnormalFrame.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        // 转正帧：reviewStatus=CONFIRMED 且测量时点≈夹具行（与 step2/3/4 录入帧按时点区分）
        Timestamp fixtureMeasuredAt = jdbcTemplate.queryForObject(
                "SELECT measured_at FROM nursing.vital_sign_record WHERE id = ?", Timestamp.class, PENDING_FIXTURE_ID);
        EventEnvelope confirmFrame = frames.stream()
                .filter(e -> "CONFIRMED".equals(e.payload().path("reviewStatus").asText()))
                .filter(e -> nearInstant(e.payload().path("measuredAt").asText(), fixtureMeasuredAt))
                .findFirst()
                .orElseThrow();
        assertThat(confirmFrame.payload().path("visitId").asText()).isEqualTo(VISIT_ID);
        assertThat(confirmFrame.payload().path("source").asText()).isEqualTo("MANUAL");
    }

    @Test
    @Order(9)
    @DisplayName("迁移索引断言：V808 idx_vital_sign_patient_time 落位且列序为 (patient_id, measured_at)")
    void step9_vitalSignPatientTimeIndexExists() {
        // PERF-02 迁移交付物断言（IotMigrationIT 断言③同款形态）：上下文启动即 Flyway 全量重放
        // 迁移链（含 V808），此处显式锚定索引落位与列序——工作站体征清单与 PDA 患者摘要按
        // patient_id 维度查询的索引范围扫描载体，防迁移静默缺失或列序颠倒导致修复意图落空
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'nursing' AND indexname = ?",
                String.class,
                "idx_vital_sign_patient_time");
        assertThat(indexDef)
                .as("PERF-02：患者维度前导索引必须存在且前导列为 patient_id、第二列为 measured_at")
                .isNotBlank()
                .contains("(patient_id, measured_at)");
    }

    /**
     * 帧测量时点与库内时点近似判定（±2s 容差）：载荷 Instant 由应用时钟生成、库内 TIMESTAMP
     * 微秒截断，且 step5/step7 前后存在毫秒级偏移——按时点锚定帧身份而非字符串全等。
     *
     * @param frameMeasuredAt 帧载荷 measuredAt 文本（ISO-8601），非空
     * @param expected        库内期望时点，非空
     * @return true=两时点相差 2 秒以内
     */
    private boolean nearInstant(String frameMeasuredAt, Timestamp expected) {
        try {
            Duration diff = Duration.between(expected.toInstant(), Instant.parse(frameMeasuredAt));
            return Math.abs(diff.toMillis()) < 2000;
        } catch (Exception e) {
            return false;
        }
    }
}

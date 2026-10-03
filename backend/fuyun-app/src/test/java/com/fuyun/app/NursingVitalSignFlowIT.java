package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.service.IVitalSignService;
import com.fuyun.nursing.vo.VitalSignVO;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import org.springframework.beans.factory.annotation.Autowired;
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
 * PR-6 M05 验收锚点①：体征归集链真栈 IT（FU-M05-02/03，零 mock HTTP/DB/MQ）。链路：入区夹具
 * （W-34 后入区唯一写入面：inpatient 入院四步→admitted/bed.changed 事件投影在区行、GET 一览
 * 回读命中）→ 体征录入（全项正常合并当日观察行 / 二次正常换行追加 / 异常独立落行）→ 体温单
 * VITAL 条目逐次写入 → 同刻同部位重复入权威栏幂等拒绝（NS-1016，事务回滚行数不变）→ 生理极限
 * 拒收（NS-1005）→ 待复核夹具行复核转正（补写体温单条目 + 二次转正 NS-1015）→ 事件帧断言
 * （nursing.vital-sign.recorded 经捕获队列消费，异常帧与转正帧按载荷锚定）。
 *
 * <p>偏差登记（简报 vs 实况，详见 task-11-report）：①简报 step5 设想「同 (visit_id,
 * measured_at, temp_site) 再 POST 被拒 NS-1019」——实况 measured_at 为服务端时间（GC25），
 * 顺序 HTTP 再录必然异刻，vital 层唯一索引经 API 不可达（DuplicateKey→NS-1016 翻译已有
 * VitalSignServiceImplTest 单测锚）；本 IT 改以同语义验证：重开待复核行再 confirm 重入权威栏，
 * 撞体温单 (page, entry_time, VITAL, type_key) 唯一键 → 409 NS-1016、行数不变、事务回滚。
 * ②简报 step1「返回 status=IN_WARD」——WardPatientVO（GC39 冻结面）无 status 组件，且 W-34
 * 退役后 nursing_ward_patient 表 status 列已删（V1108，在册谓词由 deleted=0 承载），入区夹具
 * 改走 inpatient 入院四步事件投影链，在区态以库态 deleted=0 + GET 一览命中锚定。
 * ③患者主档行经 jdbcTemplate 直插（resolve 依赖，OutpatientFullFlowIT:422 同款主数据夹具，
 * 非视图行验收）。
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

    /** 体征链住院就诊号（step1 入院链登记确认签发 I 型 14 位；断言锚经 static 跨用例传递） */
    private static String visitId = "";

    /** 体征链病区床位（W01 内 IT 自备床——inpatient.bed 零种子，事件链入区载体） */
    private static final long BED_ID = 920071L;

    private static final String BED_NO = "IT20-01";

    /** 投影收敛轮询上限（覆盖 MQ 真实投递与乱序自愈 500ms 重试窗口） */
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(10);

    /** 轮询步长 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 待复核体征夹具行 id（状态机边界夹具：PENDING_REVIEW 行经 jdbcTemplate 构造，批复口径允许项） */
    private static final long PENDING_FIXTURE_ID = 920601L;

    /** ALGO-01 对照夹具：早刻孤立行 id（比 tie 组早 10 分钟，锚定升序中间位） */
    private static final long TIE_EARLIER_ID = 920611L;

    /** ALGO-01 对照夹具：同刻 tie 组较小 id 行（与 TIE_LAST_ID 同 measured_at） */
    private static final long TIE_FIRST_ID = 920612L;

    /** ALGO-01 对照夹具：同刻 tie 组较大 id 行（确定性 tie-break 期望命中行） */
    private static final long TIE_LAST_ID = 920613L;

    /** 体征域服务（ALGO-01 真栈对照：新旧取值路径直调比对） */
    @Autowired
    private IVitalSignService vitalSignService;

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
        req.put("visitId", visitId)
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
                "SELECT count(*) FROM nursing.vital_sign_record WHERE visit_id = ?", Integer.class, visitId);
        return count == null ? 0 : count;
    }

    /** 该就诊体温单 VITAL 条目总数（转正补写与重复入栏碰撞断言锚）。 */
    private int chartVitalCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry e"
                        + " JOIN nursing.temperature_chart_page p ON p.id = e.page_id"
                        + " WHERE p.visit_id = ? AND e.entry_type = 'VITAL'",
                Integer.class,
                visitId);
        return count == null ? 0 : count;
    }

    /** 该就诊自动归集观察行总数（合并/新建分支断言锚）。 */
    private int observationRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_record WHERE visit_id = ? AND auto_generated = true",
                Integer.class,
                visitId);
        return count == null ? 0 : count;
    }

    @Test
    @Order(1)
    @DisplayName("入区夹具（W-34 换源）：inpatient 入院四步→admitted/bed.changed 投影在册行（W01 床号补齐），GET 一览回读命中")
    void step1_registerWardPatientViaAdmissionChain() {
        token = loginToken(ADMIN_LOGIN_NAME);
        // 主数据夹具：患者主档行（resolve 归一依赖；OutpatientFullFlowIT 同款直插先例）
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 体征患者");
        // W01 自备床 + 入院四步真链（W-34 后入区唯一写入面=事件投影，POST /ward-patients 已退役）
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, 'W01', 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
                BED_NO);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", "W01")
                .put("issuedDoctorId", "3");
        String admissionNo = toNode(postForEntity("/api/v1/inpatient/admissions", token, create)
                        .getBody())
                .path("admissionNo")
                .asText();
        assertThat(admissionNo).as("住院证号应签发").isNotBlank();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", "W01")
                .put("targetBedId", BED_ID)
                // 期望入住日按北京钟面取当日（时区红线：裸 now() 在 CI UTC 深夜窗错归前一日）
                .put("expectDate", LocalDate.now(TimeConstants.HEALTHCARE_TZ).toString());
        assertThat(postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", token, schedule)
                        .getStatusCode()
                        .is2xxSuccessful())
                .as("预约入院应 2xx")
                .isTrue();
        visitId = toNode(postForEntity(
                                "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                                token,
                                objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                        .getBody())
                .path("visitId")
                .asText();
        assertThat(visitId).as("登记确认应签发 I 型 14 位 visit_id").hasSize(14);
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", "W01").put("bedId", BED_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", token, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .as("入科确认应 2xx（admitted/bed.changed 发布）")
                .isTrue();
        awaitWardPatientRow();
        // 行落库断言（在区谓词 V1108 后由逻辑删单独承载；WardPatientVO 无状态组件故取库态）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT patient_id, ward_id, bed_no, deleted FROM nursing.nursing_ward_patient WHERE visit_id = ?",
                visitId);
        assertThat(((Number) row.get("deleted")).intValue())
                .as("投影行应为在册态（deleted=0）")
                .isZero();
        assertThat(((Number) row.get("patient_id")).longValue()).isEqualTo(PATIENT_ID);
        assertThat(row.get("ward_id")).as("投影归属=入科病区").isEqualTo("W01");
        assertThat(row.get("bed_no")).as("床号=bed.changed 载荷补齐").isEqualTo(BED_NO);
        // 真实流转一览验收：GET /ward-patients 回读命中（禁以直插视图行冒充验收）
        JsonNode list = getJson("/api/v1/nursing/ward-patients?wardId=W01", token);
        assertThat(list.isArray()).isTrue();
        boolean hit = false;
        for (JsonNode item : list) {
            hit = hit || visitId.equals(item.path("visitId").asText());
        }
        assertThat(hit).as("在区一览应回读命中投影行").isTrue();
    }

    /** 轮询等待护理投影在册行落库且床号补齐（admitted/bed.changed 消费收敛；超时即失败禁静默降级）。 */
    private void awaitWardPatientRow() {
        long deadline = System.currentTimeMillis() + PROJECTION_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer ready = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM nursing.nursing_ward_patient"
                            + " WHERE visit_id = ? AND deleted = 0 AND bed_no IS NOT NULL AND bed_no <> ''",
                    Integer.class,
                    visitId);
            if (ready != null && ready > 0) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("护理投影在册行未落库（visitId=" + visitId + "）");
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
                visitId);
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
                visitId);
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
                visitId);
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
                visitId,
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
        assertThat(abnormalFrame.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(abnormalFrame.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        // 转正帧：reviewStatus=CONFIRMED 且测量时点≈夹具行（与 step2/3/4 录入帧按时点区分）
        Timestamp fixtureMeasuredAt = jdbcTemplate.queryForObject(
                "SELECT measured_at FROM nursing.vital_sign_record WHERE id = ?", Timestamp.class, PENDING_FIXTURE_ID);
        EventEnvelope confirmFrame = frames.stream()
                .filter(e -> "CONFIRMED".equals(e.payload().path("reviewStatus").asText()))
                .filter(e -> nearInstant(e.payload().path("measuredAt").asText(), fixtureMeasuredAt))
                .findFirst()
                .orElseThrow();
        assertThat(confirmFrame.payload().path("visitId").asText()).isEqualTo(visitId);
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

    @Test
    @Order(10)
    @DisplayName("ALGO-01 行为保持对照：latestByPatient 单行点查与旧升序末位取值一致，同刻 tie 确定性取 id 最大行")
    void step10_latestByPatientMatchesAscendingTailSelection() {
        // 对照夹具：单语句三行——tie 组两行同 now()（同事务时间戳恒等、异部位避开
        // uk_vital_sign_visit_time_site 唯一第四维，同刻异部位并存为业务允许形态）+ 一行早
        // 10 分钟；本步执行时刻晚于 step2/3/4 录入行，tie 组即患者全史最近测量时点
        jdbcTemplate.update(
                "INSERT INTO nursing.vital_sign_record"
                        + " (id, visit_id, patient_id, ward_id, measured_at, temperature, temp_site, source,"
                        + " review_status, abnormal_flag, created_by, updated_by)"
                        + " VALUES (?, ?, ?, 'W01', now() - interval '10 minutes', 36.5, 'AXILLARY', 'MANUAL',"
                        + " 'CONFIRMED', false, 'it-fixture', 'it-fixture'),"
                        + " (?, ?, ?, 'W01', now(), 36.6, 'ORAL', 'MANUAL', 'CONFIRMED', false, 'it-fixture', 'it-fixture'),"
                        + " (?, ?, ?, 'W01', now(), 36.9, 'RECTAL', 'MANUAL', 'CONFIRMED', false, 'it-fixture', 'it-fixture')",
                TIE_EARLIER_ID,
                visitId,
                PATIENT_ID,
                TIE_FIRST_ID,
                visitId,
                PATIENT_ID,
                TIE_LAST_ID,
                visitId,
                PATIENT_ID);
        // 旧取值路径：升序全量清单取末位（ALGO-01 改造前的 PDA 摘要取数口径）
        List<VitalSignVO> legacy = vitalSignService.listByPatient(PATIENT_ID, null, null);
        VitalSignVO legacyTail = legacy.get(legacy.size() - 1);
        // 新取值路径：ORDER BY measured_at DESC, id DESC LIMIT 1 单行点查
        VitalSignVO latest = vitalSignService.latestByPatient(PATIENT_ID);
        assertThat(latest).as("有体征史患者点查必须命中单行").isNotNull();
        // 对照①（行为保持）：与旧升序末位同为最近测量时点——对外可观察行为不变
        assertThat(latest.measuredAt()).isEqualTo(legacyTail.measuredAt());
        // 对照②（tie 确定化）：同刻两行收敛取 id 最大（最新落卡）行，消除旧路径 DB 返回顺序漂移
        assertThat(latest.id()).isEqualTo(TIE_LAST_ID);
    }

    @Test
    @Order(11)
    @DisplayName("D-22 PDA 弱网重试重放：同 clientMsgId 二次提交重放返回原行（200 同 id 同时点），行数/条目/观察行零新增")
    void step11_clientMsgIdRetryReplaysOriginalRow() {
        int vitalBefore = vitalCount();
        int chartBefore = chartVitalCount();
        int observationBefore = observationRowCount();
        String clientMsgId = "it-d22-retry-0001";
        ObjectNode first = vitalBody("36.9", "ORAL");
        first.put("clientMsgId", clientMsgId);
        ResponseEntity<String> firstResp = postForEntity("/api/v1/nursing/vital-signs", token, first);
        assertThat(firstResp.getStatusCode().value())
                .as("首提应 200，实况：%s", firstResp.getBody())
                .isEqualTo(200);
        long firstId = toNode(firstResp.getBody()).path("id").asLong();
        String firstMeasuredAt = toNode(firstResp.getBody()).path("measuredAt").asText();
        // 弱网重试形态：同键再提交（measured_at 服务端时间必然异刻，唯一撞的是 uk_vital_sign_client_msg）
        ObjectNode retry = vitalBody("36.9", "ORAL");
        retry.put("clientMsgId", clientMsgId);
        ResponseEntity<String> retryResp = postForEntity("/api/v1/nursing/vital-signs", token, retry);
        assertThat(retryResp.getStatusCode().value())
                .as("同键重试应重放 200 而非 409，实况：%s", retryResp.getBody())
                .isEqualTo(200);
        assertThat(toNode(retryResp.getBody()).path("id").asLong())
                .as("重放应返回原行 id（非新落卡行）")
                .isEqualTo(firstId);
        // 重放返回首值测量时点（原行本体）：DB 回读以 µs 精度/UTC 偏移渲染同一时刻，
        // 字符串全等会误判偏移形态——毫秒内对齐即证「原时点」而非新测量
        assertThat(Instant.parse(toNode(retryResp.getBody()).path("measuredAt").asText()))
                .as("重放返回首值测量时点（原行本体）")
                .isCloseTo(Instant.parse(firstMeasuredAt), within(1, ChronoUnit.MILLIS));
        // 重试零二次副作用：体征行/体温单条目恰 +1（首提），观察行零新增（重放短路归集）
        assertThat(vitalCount()).as("重试不得新增体征行").isEqualTo(vitalBefore + 1);
        assertThat(chartVitalCount()).as("重试不得新增体温单条目").isEqualTo(chartBefore + 1);
        assertThat(observationRowCount()).as("重试不得新增观察行").isEqualTo(observationBefore);
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

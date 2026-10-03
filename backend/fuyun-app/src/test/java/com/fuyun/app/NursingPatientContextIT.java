package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * PR-6 M05 验收锚点③（W-34 退役后换源重跑，Task 7）：患者上下文拦截链真栈 IT（CF-3 消费端
 * 拦截，Spec 验收项）。链路：FROZEN 档案三入口拦截（体征录入 / 护理记录创建 / PDA 标识解析
 * 患者摘要，均 409 NS-1004 且零新增行）+ <b>过渡通道退役核验</b>（POST /ward-patients 与
 * POST /ward-patients/{visitId}/remove 两端点 404/405）+ <b>投影换源真链路夹具</b>（直调
 * inpatient admission API 四段链：住院证登记 → 预约入院 → 登记确认签发 I 型 visit_id → 入科
 * 确认——inpatient.visit.admitted + bed.changed 经 fy.topic 真实投递，InpatientVisitEventListener
 * 四路消费落 nursing_ward_patient 投影行）→ MERGED 从档入科收敛存活主档（投影行落主档 ID、
 * PDA 卡路径摘要出参亦为主档 ID）→ 跨患者巡视打卡拒（归属双因子校验）。
 *
 * <p>夹具口径（批复 2026-09-22 条件 3 允许项）：FROZEN/MERGED 患者状态经 jdbcTemplate 构造
 * （patient.patient 状态列 status + 合并指针列 merged_into_patient_id，V100 词表
 * NORMAL/FROZEN/MERGED 实测）；PDA 卡路径标识经 patient.patient_identifier 直插（value_hash
 * 以 TEST_MAC_KEY 同源 HMAC-SHA256 计算，PatientFieldCrypto.hash 同算法）；床位主数据经
 * inpatient.bed 直插（V903 零种子，InpatientAdmissionFlowIT 同款）。夹具构造于任何 resolve
 * 之前——两级缓存（L1 10s/L2 60s）无陈旧视图，首次解析即读库定性。
 *
 * <p>投影收敛等待（await 轮询）：admitted 与 bed.changed(OCCUPIED) 同事务异队列发布、消费无
 * 顺序保证——投影行落库与床号补齐各按 10s 上限轮询（listener 乱序自愈 500ms 重试覆盖常规
 * 竞序窗口），超时即失败（C1 收口的验证面：V1108 列退役 + 事件投影单一写入面在真栈可用）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NursingPatientContextIT extends FuyunStackITBase {

    /** 类级独占三容器（GC9 红线：容器禁收敛入基类——各 IT 独占一套，防捕获队列串扰） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（患者上下文两级缓存 L2 + 会话 + 住院 visit_id 发号） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（admitted/bed.changed 真实投递链——投影写入面的传输底座） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 冻结档案患者主索引（直插 FROZEN 态夹具） */
    private static final long FROZEN_PATIENT_ID = 930001L;

    /** 冻结档案的就诊卡标识值（PDA 卡路径解析载体；20 位非 18X 形态判 VISIT_CARD） */
    private static final String FROZEN_CARD = "IT-CARD-FROZEN-0001";

    /** 冻结档案标识行 id */
    private static final long FROZEN_IDENTIFIER_ID = 930011L;

    /** 冻结患者名义就诊号（退役端点核验的 URL 锚与零新增断言锚） */
    private static final String FROZEN_VISIT_ID = "I2026092300011";

    /** 合并夹具：存活主档（NORMAL，收敛目标——入科链在 inpatient 侧收敛后签发就诊） */
    private static final long SURVIVOR_PATIENT_ID = 930101L;

    /** 合并夹具：从档（MERGED + 指针指向主档，admission 创建即收敛主档；卡路径 PDA 解析载体） */
    private static final long MERGED_PATIENT_ID = 930102L;

    /** 从档就诊卡标识值与标识行 id（PDA 卡路径经盲索引归一从档 → 收敛主档） */
    private static final String MERGED_CARD = "IT-CARD-MERGED-0001";

    private static final long MERGED_IDENTIFIER_ID = 930112L;

    /** 巡视归属校验用第二患者主索引（正常档，与从档主档互异——独立走完整入科链） */
    private static final long OTHER_PATIENT_ID = 930201L;

    /** 本 IT 病区与两张床（SQL 直插——V903 bed 表零种子行，IT 自备主数据） */
    private static final String WARD_ID = "W-IT-9301";

    private static final long BED_ID_MERGED = 930111L;

    private static final String BED_NO_MERGED = "IT31-01";

    private static final long BED_ID_OTHER = 930211L;

    private static final String BED_NO_OTHER = "IT31-02";

    /** 北京钟面（入科 expectDate 与医疗日同源推导，时区纪律 A 类：禁裸 LocalDate.now()） */
    private static final ZoneId BEIJING_TZ = ZoneId.of("Asia/Shanghai");

    /** 投影收敛轮询上限（覆盖 MQ 真实投递与乱序自愈 500ms 重试窗口） */
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(10);

    /** 轮询步长 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    /** 跨用例登录令牌 */
    private static String token = "";

    /** 从档入科链签发的 visit_id（step5 后传递巡视/断言锚） */
    private static String mergedVisitId = "";

    /** 第二患者入科链签发的 visit_id */
    private static String otherVisitId = "";

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

    /** 携 Bearer 的请求头构造（GET 直连 exchange 场景复用）。 */
    private HttpHeaders bearerHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return headers;
    }

    /**
     * 完整入科真链路（投影换源夹具，InpatientAdmissionFlowIT 四段链同型）：住院证登记 →
     * 预约入院（床位 FREE→RESERVED 预占）→ 登记确认（同事务签发 I 型 14 位 visit_id）→
     * 入科确认（床位 OCCUPIED + admitted/bed.changed 事务内发布）。
     *
     * @param patientId 入科患者主索引（MERGED 从档在 inpatient 侧收敛主档后签发就诊），非空
     * @param bedId     目标床位 id（FREE 态种子行），非空
     * @return 入科签发的 visit_id，非空
     */
    private String admitPatientViaRealChain(long patientId, long bedId) {
        // 段①：住院证登记（WAITING 候床队列）
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", patientId)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_ID)
                .put("diagnosisSummary", "IT 验收：护理投影换源入科链")
                .put("issuedDoctorId", "3");
        String admissionNo = toNode(postForEntity("/api/v1/inpatient/admissions", token, create)
                        .getBody())
                .path("admissionNo")
                .asText();
        assertThat(admissionNo).as("住院证号应签发").isNotBlank();
        // 段②：预约入院（床位预占联动 FREE→RESERVED）
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_ID)
                .put("targetBedId", bedId)
                .put("expectDate", LocalDate.now(BEIJING_TZ).toString());
        ResponseEntity<String> scheduled =
                postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", token, schedule);
        assertThat(scheduled.getStatusCode().is2xxSuccessful())
                .as("预约入院应成功，实况：%s", scheduled.getBody())
                .isTrue();
        // 段③：登记确认（同事务签发 I 型 visit_id）
        ObjectNode register = objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO");
        JsonNode registered =
                toNode(postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/register", token, register)
                        .getBody());
        String visitId = registered.path("visitId").asText();
        assertThat(visitId).as("登记确认应签发 I 型 visit_id").hasSize(14);
        // 段④：入科确认（admitted + bed.changed 事务内发布——nursing 投影写入面真实触达）
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("deptId", "DEP-IT-9301")
                .put("wardId", WARD_ID)
                .put("bedId", bedId)
                .put("nursingLevel", "NORMAL")
                .put("attendingDoctorId", "3");
        ResponseEntity<String> admitted =
                postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", token, admit);
        assertThat(admitted.getStatusCode().is2xxSuccessful())
                .as("入科确认应成功（admitted/bed.changed 事件发布），实况：%s", admitted.getBody())
                .isTrue();
        return visitId;
    }

    /**
     * 投影收敛轮询（C1 收口验证面）：按条件轮询至谓词命中或超时——超时即失败（事件链断裂
     * 或消费异常显式暴露，禁静默降级断言）。
     *
     * @param condition 收敛判定谓词（true=已收敛），非空
     * @param what      断言面描述（失败消息载体），非空
     */
    private void awaitProjection(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + PROJECTION_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("投影收敛轮询被中断：" + what, e);
            }
        }
        throw new IllegalStateException("投影收敛超时（10s）：" + what);
    }

    @Test
    @Order(1)
    @DisplayName("W-34 退役核验：POST /ward-patients 与 /{visitId}/remove 两端点 404/405（过渡通道整体退役）")
    void step1_retiredWriteEndpointsAreGone() {
        token = loginToken(ADMIN_LOGIN_NAME);
        // 冻结档案夹具（后续三入口拦截用；构造于任何 resolve 之前，无陈旧缓存视图）
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'FROZEN', 'WINDOW')",
                FROZEN_PATIENT_ID,
                "IT 冻结患者");
        jdbcTemplate.update(
                "INSERT INTO patient.patient_identifier"
                        + " (id, patient_id, identifier_type, identifier_value_cipher, value_hash, status, is_primary)"
                        + " VALUES (?, ?, 'VISIT_CARD', ?, ?, 'ACTIVE', false)",
                FROZEN_IDENTIFIER_ID,
                FROZEN_PATIENT_ID,
                "aXQtZml4dHVyZS1jaXBoZXI=",
                hmacSha256(FROZEN_CARD));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("visitId", FROZEN_VISIT_ID)
                .put("patientId", FROZEN_PATIENT_ID)
                .put("wardId", WARD_ID)
                .put("bedNo", "01")
                .put("patientName", "IT 冻结患者");
        ResponseEntity<String> register = postForEntity("/api/v1/nursing/ward-patients", token, body);
        assertThat(register.getStatusCode().value())
                .as("入区登记端点退役后应 404/405（无 POST 映射）")
                .isIn(404, 405);
        ResponseEntity<String> remove = postForEntity(
                "/api/v1/nursing/ward-patients/" + FROZEN_VISIT_ID + "/remove",
                token,
                objectMapper.createObjectNode().put("reason", "退役核验"));
        assertThat(remove.getStatusCode().value()).as("移出端点退役后应 404/405（无映射）").isIn(404, 405);
        // 投影面零残留锚：退役端点不再产生任何投影行（写入面=事件消费单一承载）
        Integer wardRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_ward_patient WHERE visit_id = ?", Integer.class, FROZEN_VISIT_ID);
        assertThat(wardRows).as("退役端点后视图行零新增").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("FROZEN 拦截·体征录入：同患者录体征 409 NS-1004，vital_sign_record 零新增")
    void step2_frozenPatientBlockedAtVitalSignRecord() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", FROZEN_VISIT_ID)
                .put("source", "MANUAL")
                .put("temperature", "36.5")
                .put("tempSite", "AXILLARY")
                .put("pulse", 80);
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/vital-signs", token, req);
        assertThat(resp.getStatusCode().value()).as("冻结患者体征录入应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1004");
        Integer vitalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.vital_sign_record WHERE visit_id = ?", Integer.class, FROZEN_VISIT_ID);
        assertThat(vitalRows).as("拦截后体征行零新增").isZero();
    }

    @Test
    @Order(3)
    @DisplayName("FROZEN 拦截·文书创建：同患者建护理记录 409 NS-1004，nursing_record 零新增")
    void step3_frozenPatientBlockedAtDocumentCreate() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", FROZEN_VISIT_ID).put("recordClass", "GENERAL").put("observation", "冻结患者文书应被拒");
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/nursing-records", token, req);
        assertThat(resp.getStatusCode().value()).as("冻结患者文书创建应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1004");
        Integer recordRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_record WHERE visit_id = ?", Integer.class, FROZEN_VISIT_ID);
        assertThat(recordRows).as("拦截后护理记录行零新增").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("FROZEN 拦截·PDA 摘要：同患者卡标识解析 409 NS-1004（解析路径拦截，非在区校验）")
    void step4_frozenPatientBlockedAtPdaSummary() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/nursing/pda/patient-summary?identifier=" + FROZEN_CARD,
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);
        assertThat(resp.getStatusCode().value()).as("冻结档案 PDA 摘要应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1004");
    }

    @Test
    @Order(5)
    @DisplayName("投影换源真链路 + MERGED 收敛：从档直调 admission 四段链入科，投影行落存活主档 ID 且床号补齐；PDA 卡路径摘要亦为主档 ID")
    void step5_mergedPatientResolvesToSurvivorViaRealAdmissionChain() {
        // 边界夹具：主档 NORMAL + 从档 MERGED 指针（单跳收敛，5 跳守卫内）+ 从档就诊卡标识
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                SURVIVOR_PATIENT_ID,
                "IT 存活主档");
        jdbcTemplate.update(
                "INSERT INTO patient.patient"
                        + " (patient_id, name, sex, status, merged_into_patient_id, register_channel)"
                        + " VALUES (?, ?, '1', 'MERGED', ?, 'WINDOW')",
                MERGED_PATIENT_ID,
                "IT 合并从档",
                SURVIVOR_PATIENT_ID);
        jdbcTemplate.update(
                "INSERT INTO patient.patient_identifier"
                        + " (id, patient_id, identifier_type, identifier_value_cipher, value_hash, status, is_primary)"
                        + " VALUES (?, ?, 'VISIT_CARD', ?, ?, 'ACTIVE', false)",
                MERGED_IDENTIFIER_ID,
                MERGED_PATIENT_ID,
                "aXQtbWVyZ2VkLWNhcmQtY2lwaGVy=",
                hmacSha256(MERGED_CARD));
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID_MERGED,
                BED_NO_MERGED,
                WARD_ID);
        // 真链路夹具（W-34 换源核心）：从档入科——admitted/bed.changed 真实投递建投影行
        mergedVisitId = admitPatientViaRealChain(MERGED_PATIENT_ID, BED_ID_MERGED);
        // 投影行落库收敛（admitted 消费面；C1 收口验证——V1108 列退役后投影写入在真栈可用）
        awaitProjection(() -> countProjection(mergedVisitId) > 0, "admitted 投影行未落库：visitId=" + mergedVisitId);
        Map<String, Object> wardRow = jdbcTemplate.queryForMap(
                "SELECT patient_id, ward_id, nursing_level FROM nursing.nursing_ward_patient WHERE visit_id = ?",
                mergedVisitId);
        // CF-3 归一语义：入科链在 inpatient 侧收敛主档签发就诊，投影行 patient_id 为主档
        assertThat(((Number) wardRow.get("patient_id")).longValue())
                .as("投影行患者 ID 应收敛主档")
                .isEqualTo(SURVIVOR_PATIENT_ID);
        assertThat(wardRow.get("ward_id")).isEqualTo(WARD_ID);
        assertThat(wardRow.get("nursing_level")).isEqualTo("NORMAL");
        // 床号补齐收敛（bed.changed 消费面；乱序自愈重试覆盖 admitted 先/后到两序）
        awaitProjection(
                () -> bedNoOf(mergedVisitId) != null && !bedNoOf(mergedVisitId).isBlank(),
                "bed.changed 床号未补齐：visitId=" + mergedVisitId);
        assertThat(bedNoOf(mergedVisitId)).isEqualTo(BED_NO_MERGED);
        // PDA 摘要（从档卡路径）：盲索引归一从档 → 收敛主档 → 命中主档在册投影行
        JsonNode summary = getJson("/api/v1/nursing/pda/patient-summary?identifier=" + MERGED_CARD, token);
        assertThat(summary.path("patientId").asLong()).as("PDA 摘要应按主档出参").isEqualTo(SURVIVOR_PATIENT_ID);
        assertThat(summary.path("wardId").asText()).isEqualTo(WARD_ID);
        assertThat(summary.path("bedNo").asText()).isEqualTo(BED_NO_MERGED);
    }

    @Test
    @Order(6)
    @DisplayName("跨患者巡视打卡拒：扫从档卡给第二患者 visitId 打卡 409 NS-1016（归属双因子校验，Task 10 实况码）")
    void step6_wrongWardPatrolRejected() {
        // 归属校验载体：第二患者正常档 + 完整入科真链路（独立投影行）
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                OTHER_PATIENT_ID,
                "IT 巡视归属患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID_OTHER,
                BED_NO_OTHER,
                WARD_ID);
        otherVisitId = admitPatientViaRealChain(OTHER_PATIENT_ID, BED_ID_OTHER);
        awaitProjection(() -> countProjection(otherVisitId) > 0, "第二患者 admitted 投影行未落库：visitId=" + otherVisitId);
        // 扫从档卡（解析收敛主档 SURVIVOR），打卡归属填第二患者就诊号 → 归属不符拒
        ObjectNode req = objectMapper.createObjectNode();
        req.put("identifier", MERGED_CARD).put("visitId", otherVisitId);
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/pda/patrol", token, req);
        assertThat(resp.getStatusCode().value())
                .as("扫码与就诊号患者不一致应 409（NS-1016 资源冲突语义）")
                .isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1016");
    }

    /** 投影行计数（在册行——逻辑删谓词显式排除）。 */
    private int countProjection(String visitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0",
                Integer.class,
                visitId);
        return count == null ? 0 : count;
    }

    /** 投影行床号读取（未落行为 null）。 */
    private String bedNoOf(String visitId) {
        return jdbcTemplate.queryForObject(
                "SELECT bed_no FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0",
                String.class,
                visitId);
    }

    /**
     * 标识值盲索引（与 PatientFieldCrypto.hash 同算法同密钥）：TEST_MAC_KEY hex 解码为
     * HmacSHA256 密钥，对标识值 UTF-8 字节取小写 hex 摘要——解析服务等值查的唯一依据。
     *
     * @param value 标识值明文（仅本方法生命周期内存活，禁入日志），非空
     * @return 64 位小写 hex 盲索引摘要，非空
     * @throws IllegalStateException HMAC 计算失败（JDK 标准算法不应抛出，环境级异常显式失败）
     */
    private static String hmacSha256(String value) {
        try {
            byte[] key = new byte[TEST_MAC_KEY.length() / 2];
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) Integer.parseInt(TEST_MAC_KEY.substring(i * 2, i * 2 + 2), 16);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("标识盲索引计算失败（环境异常）", e);
        }
    }
}

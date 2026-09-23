package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
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
 * PR-6 M05 验收锚点③：患者上下文拦截链真栈 IT（CF-3 消费端拦截，Spec 验收项）。链路：
 * FROZEN 档案四入口拦截（入区登记 / 体征录入 / 护理记录创建 / PDA 标识解析患者摘要，均
 * 409 NS-1004 且零新增行）→ MERGED 从档解析收敛存活主档（入区登记落主档 ID、PDA 摘要出参
 * 亦为主档 ID）→ 跨患者巡视打卡拒（归属双因子校验）。
 *
 * <p>夹具口径（批复 2026-09-22 条件 3 允许项）：FROZEN/MERGED 患者状态经 jdbcTemplate 构造
 * （patient.patient 状态列 status + 合并指针列 merged_into_patient_id，V100 词表
 * NORMAL/FROZEN/MERGED 实测）；PDA 卡路径标识经 patient.patient_identifier 直插（value_hash
 * 以 TEST_MAC_KEY 同源 HMAC-SHA256 计算，PatientFieldCrypto.hash 同算法）。夹具构造于任何
 * resolve 之前——两级缓存（L1 10s/L2 60s）无陈旧视图，首次解析即读库定性。
 *
 * <p>偏差登记（简报 vs 实况，详见 task-11-report）：简报 step6 写「巡视打卡传他患者 visitId →
 * 400 NS-1019」——Task 10 实装为归属不符资源冲突 409 NS-1016（GC14 权威：NS-1016=归属不符，
 * NS-1019=入参格式），按实况断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NursingPatientContextIT extends FuyunStackITBase {

    /** 类级独占三容器（GC9 红线：容器禁收敛入基类——各 IT 独占一套，防捕获队列串扰） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（患者上下文两级缓存 L2 + 会话） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（本链无事件断言，容器随全栈形态在位保真） */
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

    /** 冻结患者入区就诊号（登记被拦截的 visit 锚） */
    private static final String FROZEN_VISIT_ID = "I2026092300011";

    /** 合并夹具：存活主档（NORMAL，收敛目标） */
    private static final long SURVIVOR_PATIENT_ID = 930101L;

    /** 合并夹具：从档（MERGED + 指针指向主档，resolve 5 跳守卫内单跳收敛） */
    private static final long MERGED_PATIENT_ID = 930102L;

    /** 从档入区就诊号（登记应成功且落主档 ID） */
    private static final String MERGED_VISIT_ID = "I2026092300021";

    /** 巡视归属校验用第二患者主索引（正常档，与从档主档互异） */
    private static final long OTHER_PATIENT_ID = 930201L;

    /** 第二患者入区就诊号（打卡归属不符载体） */
    private static final String OTHER_VISIT_ID = "I2026092300031";

    /** 跨用例登录令牌 */
    private static String token = "";

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

    /** 入区登记请求体构造。 */
    private ObjectNode registerBody(String visitId, long patientId, String bedNo, String name) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", visitId)
                .put("patientId", patientId)
                .put("wardId", "W01")
                .put("bedNo", bedNo)
                .put("patientName", name)
                .put("nursingLevel", "NORMAL");
        return req;
    }

    @Test
    @Order(1)
    @DisplayName("FROZEN 拦截·入区登记：409 NS-1004，nursing_ward_patient 零新增（resolve 首解析即读库定性）")
    void step1_frozenPatientBlockedAtWardRegister() {
        token = loginToken(ADMIN_LOGIN_NAME);
        // 边界夹具：冻结档案 + 就诊卡标识（构造于任何 resolve 之前，无陈旧缓存视图）
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
        ResponseEntity<String> resp = postForEntity(
                "/api/v1/nursing/ward-patients",
                token,
                registerBody(FROZEN_VISIT_ID, FROZEN_PATIENT_ID, "01", "IT 冻结患者"));
        assertThat(resp.getStatusCode().value()).as("冻结档案入区登记应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1004");
        Integer wardRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_ward_patient WHERE visit_id = ?", Integer.class, FROZEN_VISIT_ID);
        assertThat(wardRows).as("拦截后视图行零新增").isZero();
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
    @DisplayName("MERGED 收敛：从档入区登记成功且视图行落存活主档 ID，PDA 摘要 patientId 亦为主档 ID")
    void step5_mergedPatientResolvesToSurvivor() {
        // 边界夹具：主档 NORMAL + 从档 MERGED 指针（单跳收敛，5 跳守卫内）
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
        // 入区登记：入参从档 ID，视图行收敛主档（CF-3 归一语义）
        ResponseEntity<String> resp = postForEntity(
                "/api/v1/nursing/ward-patients",
                token,
                registerBody(MERGED_VISIT_ID, MERGED_PATIENT_ID, "21", "IT 合并从档"));
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("MERGED 从档登记应成功（收敛不拦截），实况：%s", resp.getBody())
                .isTrue();
        Map<String, Object> wardRow = jdbcTemplate.queryForMap(
                "SELECT patient_id, status FROM nursing.nursing_ward_patient WHERE visit_id = ?", MERGED_VISIT_ID);
        assertThat(((Number) wardRow.get("patient_id")).longValue())
                .as("视图行患者 ID 应收敛主档")
                .isEqualTo(SURVIVOR_PATIENT_ID);
        assertThat(wardRow.get("status")).isEqualTo("IN_WARD");
        // PDA 摘要（腕带编码路径）：出参 patientId 亦为收敛主档
        JsonNode summary = getJson("/api/v1/nursing/pda/patient-summary?identifier=" + MERGED_VISIT_ID, token);
        assertThat(summary.path("patientId").asLong()).as("PDA 摘要应按主档出参").isEqualTo(SURVIVOR_PATIENT_ID);
        assertThat(summary.path("wardId").asText()).isEqualTo("W01");
        assertThat(summary.path("bedNo").asText()).isEqualTo("21");
    }

    @Test
    @Order(6)
    @DisplayName("跨患者巡视打卡拒：扫甲患者腕带给乙患者 visitId 打卡 409 NS-1016（归属双因子校验，Task 10 实况码）")
    void step6_wrongWardPatrolRejected() {
        // 归属校验载体：第二患者正常入区（真实流转）
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                OTHER_PATIENT_ID,
                "IT 巡视归属患者");
        ResponseEntity<String> registered = postForEntity(
                "/api/v1/nursing/ward-patients",
                token,
                registerBody(OTHER_VISIT_ID, OTHER_PATIENT_ID, "31", "IT 巡视归属患者"));
        assertThat(registered.getStatusCode().is2xxSuccessful())
                .as("第二患者登记夹具应 2xx")
                .isTrue();
        // 扫 step5 从档主档的腕带（visit=MERGED_VISIT_ID），打卡归属填第二患者就诊号 → 归属不符拒
        ObjectNode req = objectMapper.createObjectNode();
        req.put("identifier", MERGED_VISIT_ID).put("visitId", OTHER_VISIT_ID);
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/pda/patrol", token, req);
        assertThat(resp.getStatusCode().value())
                .as("扫码与就诊号患者不一致应 409（NS-1016 资源冲突语义）")
                .isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1016");
    }

    /** 携 Bearer 的请求头构造（GET 直连 exchange 场景复用）。 */
    private HttpHeaders bearerHeaders(String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return headers;
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

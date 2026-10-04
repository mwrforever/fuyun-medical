package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.constants.TimeConstants;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.EmptyResultDataAccessException;
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
 * PR-3 验收锚点⑤（W-34 病区患者通道退役与纯事件投影，真栈全链）：POST /ward-patients 退役
 * 写路径不可达断言（同路径 GET 读面在册→实际路由 405，与 brief 设想 404 的偏差申报见任务报告）+ admitted/transferred/discharged 事件链驱动投影三态（在区
 * 行落位/转科归属切换/出院逻辑删）+ GET 一览 GC39 六字段值断言（visitId/patientId/wardId/
 * bedNo/nursingLevel/admittedAt——字段值全部由四事件载荷推导实证）。
 *
 * <p><b>事件链真实形态</b>：不经信封注入——走 inpatient REST 全链（入院四步→admit-ward 发布
 * admitted+bed.changed；转科编排发布 transferred；出院确认发布 discharged），nursing 侧四路
 * 消费（q.nursing.inpatient.*）真实落投影（InpatientAdmissionFlowIT/InpatientTransferDischargeIT
 * 先例逐段组装）。投影三态承载：在区=deleted=0 行、转科=ward_id 切换、出院=deleted=1（V1108
 * 后无 status 列，在区谓词由逻辑删承载）。
 *
 * <p>容器三件套类级独占（GC9 红线，InpatientOrderFlowIT :74-88 逐字同型）。
 *
 * <p><b>PR-4C Task 6（W-40）适配</b>：一览 GET /ward-patients 挂病区守卫（fail-closed）——
 * admin 的 V1114 种子绑定只覆盖 W01，本 IT 锚 W-IT-9005/W-IT-9006 双病区，Order(2) 夹具段
 * 补双病区当班绑定行（brief 预检之外的实况新增，D-21 申报见任务报告）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WardPatientRetirementIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（就诊流水键/缓存载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（住院事件族消费链载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 转出/转入病区与床位（SQL 直插主数据，InpatientTransferDischargeIT 同型） */
    private static final String WARD_FROM = "W-IT-9005";

    private static final String WARD_TO = "W-IT-9006";

    private static final long BED_FROM_ID = 920511L;

    private static final long BED_TO_ID = 920521L;

    private static final String BED_FROM_NO = "IT9-51";

    private static final String BED_TO_NO = "IT9-61";

    private static final long PATIENT_ID = 920501L;

    /** V704 演示医师（出院申请/离院确认操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** MQ 消费链路等待上限（投影四路消费异步收敛） */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String requestNo = "";

    /** 带 Bearer 的 GET 助手（InpatientOrderFlowIT 同型）。 */
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

    /** 带令牌 POST（返回原始响应实体，状态码与体断言双取）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 投影行整行读取（GC39 六字段的库侧对照锚）。 */
    private Map<String, Object> projectionRow() {
        return jdbcTemplate.queryForMap(
                "SELECT ward_id, bed_no, patient_id, nursing_level, admitted_at, deleted"
                        + " FROM nursing.nursing_ward_patient WHERE visit_id = ?",
                visitId);
    }

    /** 投影行逻辑删标记（出院终态断言锚）。 */
    private int projectionDeleted() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT deleted FROM nursing.nursing_ward_patient WHERE visit_id = ?", Integer.class, visitId);
        return n == null ? -1 : n;
    }

    /** 轮询等待投影行到达目标病区且床号非空（admitted/transferred 消费收敛）。 */
    private void awaitProjection(String wardId) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row;
            try {
                row = projectionRow();
            } catch (EmptyResultDataAccessException e) {
                row = null;
            }
            if (row != null
                    && wardId.equals(row.get("ward_id"))
                    && row.get("bed_no") != null
                    && !((String) row.get("bed_no")).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("投影行未到达目标病区（wardId=" + wardId + "）：" + projectionRow());
    }

    /** 轮询等待投影行逻辑删（discharged 消费收敛）。 */
    private void awaitProjectionDeleted() {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (projectionDeleted() == 1) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // E-2：超时即本处红，防失败漂移至下游断言
        fail("等待超时：REMOVED 投影行未在时限内删除（" + projectionRow() + "）");
    }

    /** 一览清单行定位（GC39 六字段断言取数面）。 */
    private JsonNode listRowOf(String wardId) {
        JsonNode list = getJson("/api/v1/nursing/ward-patients?wardId=" + wardId, adminToken);
        assertThat(list.isArray()).as("一览应返回数组").isTrue();
        for (JsonNode row : list) {
            if (visitId.equals(row.path("visitId").asText())) {
                return row;
            }
        }
        return MissingNode.getInstance();
    }

    @Test
    @Order(1)
    @DisplayName("退役断言：POST /ward-patients 已退役写路径不可达（405——同路径仅余 GET 读面）")
    void retiredWriteEndpointIsUnreachable() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", "I2026100300009")
                .put("patientId", PATIENT_ID)
                .put("wardId", WARD_FROM)
                .put("bedNo", "01")
                .put("patientName", "IT 退役患者")
                .put("nursingLevel", "NORMAL");
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/ward-patients", adminToken, req);
        // 退役实况裁决（brief 设想 404 与实况偏差）：同路径 GET 一览读面仍在册（端点面冻结清单），
        // Spring 对未映射 POST 方法返回 405——写路径不可达语义等价（W-34 禁写路径下渗冻结锚按
        // 实际路由形态断言，偏差申报见任务报告）
        assertThat(resp.getStatusCode().value())
                .as("入区登记写端点已退役（同路径仅余 GET 读面，POST 405）")
                .isEqualTo(405);
    }

    @Test
    @Order(2)
    @DisplayName("admitted 事件链：入院四步→投影在区行（wardId/bedNo/nursingLevel/admittedAt）+一览 GC39 六字段")
    void admittedEventDrivesProjectionRow() {
        // W-40 守卫适配：admin 调一览 GET 须有目标病区当班绑定行（V1114 种子只覆盖 W01）——
        // 照 Task 5 种子行形态直插双病区绑定（长期有效窗当日命中）
        jdbcTemplate.update(
                "INSERT INTO nursing.nurse_assignment"
                        + " (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,"
                        + " valid_from, valid_to, status, created_by, updated_by, deleted)"
                        + " VALUES (?, ?, '1', 'PRIMARY', 'DAY', NULL, NULL,"
                        + " DATE '2026-01-01', NULL, 'ACTIVE', 'IT', 'IT', 0),"
                        + " (?, ?, '1', 'PRIMARY', 'DAY', NULL, NULL,"
                        + " DATE '2026-01-01', NULL, 'ACTIVE', 'IT', 'IT', 0)",
                9114000000000000102L,
                WARD_FROM,
                9114000000000000103L,
                WARD_TO);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 退役链患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE'), (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_FROM_ID,
                BED_FROM_NO,
                WARD_FROM,
                BED_TO_ID,
                BED_TO_NO,
                WARD_TO);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_FROM)
                .put("issuedDoctorId", "3");
        String admissionNo = postJson("/api/v1/inpatient/admissions", adminToken, create)
                .path("admissionNo")
                .asText();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_FROM)
                .put("targetBedId", BED_FROM_ID)
                // 期望入住日按北京钟面取当日（时区红线：裸 now() 在 CI UTC 深夜窗错归前一日）
                .put("expectDate", LocalDate.now(TimeConstants.HEALTHCARE_TZ).toString());
        assertThat(postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_FROM).put("bedId", BED_FROM_ID).put("nursingLevel", "CRITICAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 投影在区态（deleted=0 行落位，bed_no 经 bed.changed 补齐——真实事件链异步收敛）
        awaitProjection(WARD_FROM);
        Map<String, Object> row = projectionRow();
        assertThat(((Number) row.get("deleted")).intValue()).as("在区行逻辑删标记 0").isZero();
        assertThat(((Number) row.get("patient_id")).longValue()).isEqualTo(PATIENT_ID);
        assertThat(row.get("nursing_level")).as("护理级别=admitted 载荷推导").isEqualTo("CRITICAL");
        assertThat(row.get("admitted_at")).as("入区时点=admitted 载荷推导").isNotNull();

        // 一览 GC39 六字段值断言（读面结构等价判据）
        JsonNode vo = listRowOf(WARD_FROM);
        assertThat(vo.isObject()).as("一览应命中在区行").isTrue();
        assertThat(vo.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("visitId", "patientId", "wardId", "bedNo", "nursingLevel", "admittedAt");
        assertThat(vo.path("visitId").asText()).isEqualTo(visitId);
        assertThat(vo.path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(vo.path("wardId").asText()).isEqualTo(WARD_FROM);
        assertThat(vo.path("bedNo").asText()).as("床号=bed.changed 载荷补齐").isEqualTo(BED_FROM_NO);
        assertThat(vo.path("nursingLevel").asText()).isEqualTo("CRITICAL");
        assertThat(vo.hasNonNull("admittedAt")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("transferred 事件链：转科编排→投影归属切 W2（床号补齐）+一览 W1 缺席/W2 命中")
    void transferredEventSwitchesProjectionWard() {
        assertThat(postForEntity(
                                "/api/v1/inpatient/beds/" + BED_TO_ID + "/reserve",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        ObjectNode transfer = objectMapper.createObjectNode();
        transfer.put("toWardId", WARD_TO).put("toBedId", BED_TO_ID);
        JsonNode vo = postJson("/api/v1/inpatient/visits/" + visitId + "/transfer", adminToken, transfer);
        assertThat(vo.path("toWardId").asText()).isEqualTo(WARD_TO);

        // 投影归属变更（transferred 消费：ward 切换；bed_no 由新床 bed.changed 补齐）
        awaitProjection(WARD_TO);
        JsonNode listRow = listRowOf(WARD_TO);
        assertThat(listRow.path("wardId").asText()).as("一览归属已切转入病区").isEqualTo(WARD_TO);
        assertThat(listRow.path("bedNo").asText()).as("床号=转入床 bed.changed 补齐").isEqualTo(BED_TO_NO);
        assertThat(listRow.path("nursingLevel").asText()).as("护理级别保持（转科不迁护理级别）").isEqualTo("CRITICAL");
        assertThat(listRowOf(WARD_FROM).isObject()).as("转出病区一览应移除该行").isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("discharged 事件链：出院申请→挂账放行→结算→离院确认→投影逻辑删+两病区一览全缺席")
    void dischargedEventLogicallyDeletesProjection() throws Exception {
        // 出院申请（费用预审 BLOCKED——床位/护理基础费用在册）
        ObjectNode create = objectMapper.createObjectNode();
        create.put("expectDischargeAt", OffsetDateTime.now().toString()).put("dischargeWay", "1");
        requestNo = postJson("/api/v1/inpatient/visits/" + visitId + "/discharge-request", doctorToken, create)
                .path("requestNo")
                .asText();
        // 挂账审批放行（双人第二账号承载审批位）
        ObjectNode approvalReq = objectMapper.createObjectNode();
        approvalReq.put("visitId", visitId).put("applyReason", "IT 验收：退役链出院放行");
        String approvalNo = postJson("/api/v1/billing/arrears-approvals", adminToken, approvalReq)
                .path("approvalNo")
                .asText();
        assertThat(postForEntity(
                                "/api/v1/billing/arrears-approvals/" + approvalNo + "/approve",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        for (int i = 0; i < 100; i++) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM inpatient.discharge_request WHERE request_no = ?", String.class, requestNo);
            if ("READY".equals(status)) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        // 真实结算（预览取总额→现金全结）
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("patientId", PATIENT_ID).put("visitId", visitId).put("payerType", "SELF_PAY");
        JsonNode pv = postJson("/api/v1/billing/settlements/preview", adminToken, preview);
        ObjectNode settle = objectMapper.createObjectNode();
        settle.put("settleNo", pv.path("settleNo").asText());
        settle.putArray("payments")
                .addObject()
                .put("method", "CASH")
                .put("amount", pv.path("totalAmount").asText());
        assertThat(postForEntity("/api/v1/billing/settlements", adminToken, settle)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        for (int i = 0; i < 100; i++) {
            Object completedAt = jdbcTemplate.queryForObject(
                    "SELECT settlement_completed_at FROM inpatient.discharge_request WHERE request_no = ?",
                    Object.class,
                    requestNo);
            if (completedAt != null) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        // 离院确认（双条件就位→DISCHARGED+discharged 事件）
        JsonNode confirmed = postJson(
                "/api/v1/inpatient/discharge-requests/" + requestNo + "/confirm",
                doctorToken,
                objectMapper.createObjectNode().put("followUpDays", 7).put("followUpWay", "PHONE"));
        assertThat(confirmed.path("status").asText()).as("离院确认后申请终态").isEqualTo("COMPLETED");

        // 投影终态：逻辑删（discharged 消费）+两病区一览全缺席
        awaitProjectionDeleted();
        assertThat(listRowOf(WARD_TO).isObject()).as("转入病区一览应移除该行").isFalse();
        assertThat(listRowOf(WARD_FROM).isObject()).as("转出病区一览不回填").isFalse();
    }
}

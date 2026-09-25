package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.service.OrderPlanService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * PR-1 验收锚点④：长期医嘱日切分解真栈（HTTP/MQ/DB 零 mock）——bid/qd 两条转抄后长期医嘱
 * 经 decomposeNextDay（与 02:30 任务同入口）分解次日计划（bid=08:00/16:00 两行+qd=08:00 一行，
 * 计划时点与班次落值逐行勾稽）→ 重复执行幂等（零新行）→ 当日增量补偿（晚转抄长期医嘱剩余时点
 * 补齐——按 impl 同源时点过滤规则推算期望集）→ 停嘱联动（STOPPED→次日计划全 CANCELLED）→
 * order-plan.generated 投递断言（planNos 数组与落库一致、planTimes 下标对齐）。
 *
 * <p>捕获队列经治理构件声明（order-plan.generated 无类型子键，ConsumerQueueSpec 精确绑定即可）；
 * raw 解析监听不进幂等台账。容器三件套类级独占（GC9 红线）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InpatientDailyDecomposeIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 本 IT 病区与床位（SQL 直插主数据） */
    private static final String WARD_ID = "W-IT-9005";

    private static final long BED_ID = 920411L;

    private static final long PATIENT_ID = 920401L;

    /** V704 演示医师（sys_user id=3 持 PRESCRIPTION 执业授权——开单操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** DB 轮询步进（捕获帧/消费面 100ms 步进轮询足够收敛，上限 10s 量级） */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态 */
    private static String adminToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String bidOrderNo = "";

    private static String qdOrderNo = "";

    /** 日切分解服务（IT 直调 decomposeNextDay=02:30 任务同入口；补偿面经转抄链自动触发）。 */
    @Autowired
    private OrderPlanService orderPlanService;

    /**
     * 捕获队列声明（A.5-4 治理红线：经治理构件声明，OutpatientFullFlowIT ItCaptureConfig 同型）。
     * order-plan.generated（V800 id 43）无类型子键，ConsumerQueueSpec 精确绑定承载。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_GENERATED =
                MessagingConstants.QUEUE_PREFIX + "it." + InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED;

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itPlanGeneratedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED));
        }

        @Bean
        ItCaptureListener itCaptureListener(EventEnvelopeCodec codec) {
            return new ItCaptureListener(codec);
        }
    }

    /** 监听器本体（测试侧轻量：解析入列，不登记 received_event）。 */
    static class ItCaptureListener {

        private final EventEnvelopeCodec codec;

        ItCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(queues = ItCaptureConfig.Q_GENERATED)
        void onMessage(Message message) {
            ItCaptureConfig.CAPTURED.add(codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8)));
        }
    }

    /** 带 Bearer 的 GET 助手。 */
    private JsonNode getJson(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String body = restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
        return toNode(body);
    }

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

    /** 带令牌 POST（返回原始响应实体，状态码断言）。 */
    private ResponseEntity<String> postForEntity(String path, String token, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode postJson(String path, String token, JsonNode body) {
        return toNode(postForEntity(path, token, body).getBody());
    }

    /** 建项目+定价+发布一步到位（OutpatientFullFlowIT 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 日切项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "次")
                .put("comboFlag", false)
                .put("feeCategory", "TREAT_FEE");
        long itemId = postJson("/api/v1/billing/charge-items", adminToken, item)
                .path("id")
                .asLong();
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("itemCode", itemCode)
                .put("price", priceFen)
                .put("effectiveFrom", OffsetDateTime.now().toString())
                .put("priceSource", "OFFICIAL_DOC");
        long priceRowId = postJson("/api/v1/billing/charge-items/" + itemId + "/prices", adminToken, draft)
                .asLong();
        postJson(
                "/api/v1/billing/price-adjustments/" + priceRowId + "/publish",
                adminToken,
                objectMapper.createObjectNode());
    }

    /** 开立一条长期 NURSING 医嘱并转抄放行（非用药类自动过审）。 */
    private void openLongOrder(String freqCode) {
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "NURSING").put("orderClass", "LONG").put("freqCode", freqCode);
        order.putArray("items")
                .addObject()
                .put("itemType", "NURSING")
                .put("itemCode", "IT-NUR-" + freqCode)
                .put("itemName", "IT 长期护理 " + freqCode)
                .put("quantity", "1");
        String orderNo = postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order)
                .path("orderNo")
                .asText();
        ObjectNode check = objectMapper.createObjectNode();
        var arr = check.putArray("orderNos");
        arr.add(orderNo);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED");
        assertThat(postForEntity("/api/v1/inpatient/orders/transfer-check", adminToken, check)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        if ("bid".equals(freqCode)) {
            bidOrderNo = orderNo;
        } else {
            qdOrderNo = orderNo;
        }
    }

    /**
     * 读该医嘱指定日期的计划行时点（HH:mm 升序）：时点以 JDBC Timestamp 的 JVM 默认时区墙面渲染，
     * 与 impl 生成器（JVM 默认 offset 构建计划时点）同源，规避 to_char 的服务端会话时区漂移。
     */
    private List<String> planTimesOn(String orderNoText, String status, LocalDate date) {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        return jdbcTemplate.query(
                "SELECT p.plan_time FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.status = ? AND p.deleted = 0"
                        + " AND p.plan_time >= ? AND p.plan_time < ? ORDER BY p.plan_time",
                (rs, i) -> rs.getTimestamp(1).toLocalDateTime().toLocalTime().toString(),
                orderNoText,
                status,
                date.atStartOfDay().atOffset(offset),
                date.plusDays(1).atStartOfDay().atOffset(offset));
    }

    /** 读该医嘱指定日期的计划行数（默认时区日期对齐 impl 的计划窗口口径）。 */
    private int planCountOn(String orderNoText, String status, LocalDate date) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.status = ? AND p.deleted = 0"
                        + " AND p.plan_time >= ? AND p.plan_time < ?",
                Integer.class,
                orderNoText,
                status,
                date.atStartOfDay().atOffset(OffsetDateTime.now().getOffset()),
                date.plusDays(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()));
        return count == null ? 0 : count;
    }

    /** 轮询等待捕获队列出现指定事件帧并返回（AFTER_COMMIT 出 MQ 异步收敛）。 */
    private EventEnvelope awaitCaptured(String eventType) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<EventEnvelope> matched = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> e.eventType().equals(eventType))
                    .toList();
            if (!matched.isEmpty()) {
                return matched.get(0);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventType);
    }

    /** 轮询等待指定事件且 planDate 匹配的帧（日切/补偿两批同型帧按计划日期分拣）。 */
    private EventEnvelope awaitCapturedForPlanDate(String eventType, LocalDate planDate) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<EventEnvelope> matched = ItCaptureConfig.CAPTURED.stream()
                    .filter(e -> e.eventType().equals(eventType))
                    .filter(e -> planDate.toString()
                            .equals(e.payload().path("planDate").asText()))
                    .toList();
            if (!matched.isEmpty()) {
                return matched.get(0);
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventType + "，planDate=" + planDate);
    }

    @Test
    @Order(1)
    @DisplayName("前置：登录+患者床位直插；入院链四步至 ADMITTED；bid/qd 两条长期医嘱开立转抄")
    void prepareTwoTransferredLongOrders() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        // 计价项目预备（billing 通配消费 created 帧逐项离散计价——种子价 1000 分即可）
        newItemWithPrice("IT-NUR-bid", 1000);
        newItemWithPrice("IT-NUR-qd", 1000);
        newItemWithPrice("IT-NUR-qn", 1000);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "日切患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, ?, ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
                "IT9-31",
                WARD_ID);
        ObjectNode create = objectMapper.createObjectNode();
        create.put("patientId", PATIENT_ID)
                .put("sourceType", "OTHER")
                .put("admissionType", "NORMAL")
                .put("targetWardId", WARD_ID)
                .put("issuedDoctorId", "3");
        String admissionNo = postJson("/api/v1/inpatient/admissions", adminToken, create)
                .path("admissionNo")
                .asText();
        ObjectNode schedule = objectMapper.createObjectNode();
        schedule.put("targetWardId", WARD_ID)
                .put("targetBedId", BED_ID)
                .put("expectDate", LocalDate.now().toString());
        postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule);
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        assertThat(visitId).matches("I\\d{13}");
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_ID).put("bedId", BED_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        openLongOrder("bid");
        openLongOrder("qd");
    }

    @Test
    @Order(2)
    @DisplayName("日切分解：decomposeNextDay(次日) 生成 2+1 行（bid 08:00/16:00 + qd 08:00），班次与计划号逐行勾稽")
    void decomposeNextDayCreatesPlanRows() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        int created = orderPlanService.decomposeNextDay(tomorrow);
        assertThat(created).as("两条长期医嘱应生成 2+1 行次日计划").isEqualTo(3);
        assertThat(planTimesOn(bidOrderNo, "PENDING", tomorrow))
                .as("bid 医嘱次日计划时点=08:00/16:00")
                .containsExactly("08:00", "16:00");
        assertThat(planTimesOn(qdOrderNo, "PENDING", tomorrow))
                .as("qd 医嘱次日计划时点=08:00")
                .containsExactly("08:00");

        // 班次落值（窗口左闭右开）：08:00→DAY、16:00→EVENING（按 JVM 墙面时点匹配，同生成器口径）
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        List<Map<String, Object>> shiftRows = jdbcTemplate.queryForList(
                "SELECT p.shift, p.ward_id, p.plan_time FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.deleted = 0"
                        + " AND p.plan_time >= ? AND p.plan_time < ? ORDER BY p.plan_time",
                bidOrderNo,
                tomorrow.atStartOfDay().atOffset(offset),
                tomorrow.plusDays(1).atStartOfDay().atOffset(offset));
        assertThat(shiftRows).as("次日计划两行在位").hasSize(2);
        Map<String, Object> morning = shiftRows.get(0);
        Map<String, Object> evening = shiftRows.get(1);
        assertThat(morning.get("shift")).as("08:00 计划落白班").isEqualTo("DAY");
        assertThat(morning.get("ward_id")).as("计划病区=患者当前病区").isEqualTo(WARD_ID);
        assertThat(((java.sql.Timestamp) morning.get("plan_time"))
                        .toLocalDateTime()
                        .toLocalDate())
                .as("计划日期=次日")
                .isEqualTo(tomorrow);
        assertThat(evening.get("shift")).as("16:00 计划落小夜班").isEqualTo("EVENING");

        // order-plan.generated 真实投递帧（V800 id 43）：planNos 数组与落库一致、planTimes 下标对齐
        // （按 planDate 定位次日帧——转抄链补偿面同日早先已发过 planDate=当日的同型帧）
        EventEnvelope generated =
                awaitCapturedForPlanDate(InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED, tomorrow);
        JsonNode payload = generated.payload();
        assertThat(payload.path("m04OrderNo").asText()).isEqualTo(bidOrderNo);
        assertThat(payload.path("visitId").asText()).isEqualTo(visitId);
        assertThat(payload.path("planDate").asText()).isEqualTo(tomorrow.toString());
        List<String> dbPlanNos = jdbcTemplate.queryForList(
                "SELECT p.plan_no FROM inpatient.order_execute_plan p"
                        + " JOIN inpatient.medical_order o ON o.id = p.order_id"
                        + " WHERE o.order_no = ? AND p.deleted = 0"
                        + " AND p.plan_time >= ? AND p.plan_time < ? ORDER BY p.plan_time",
                String.class,
                bidOrderNo,
                tomorrow.atStartOfDay().atOffset(offset),
                tomorrow.plusDays(1).atStartOfDay().atOffset(offset));
        assertThat(payload.path("planNos").toString().replace("\"", ""))
                .as("planNos 数组与落库计划号一致")
                .contains(dbPlanNos.get(0), dbPlanNos.get(1));
        assertThat(payload.path("planTimes").toString())
                .as("planTimes 下标对齐时点序列")
                .contains("08:00", "16:00");
    }

    @Test
    @Order(3)
    @DisplayName("重复执行幂等：同参数重跑日切零新行，计划行数不变")
    void decomposeRerunIsIdempotent() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        int secondRun = orderPlanService.decomposeNextDay(tomorrow);
        assertThat(secondRun).as("重复日切应零新行（查前置+唯一约束双幂等）").isZero();
        assertThat(planTimesOn(bidOrderNo, "PENDING", tomorrow)).hasSize(2);
        assertThat(planTimesOn(qdOrderNo, "PENDING", tomorrow)).hasSize(1);
    }

    @Test
    @Order(4)
    @DisplayName("当日增量补偿：晚于日切的新转抄长期医嘱仅补生成 now 之后剩余时点（与 impl 同源过滤规则）")
    void lateTransferredLongOrderCompensatesRemainingPoints() {
        // 第三条长期医嘱（qn 20:00）转抄——转抄链内自动触发 compensateToday
        OffsetDateTime now = OffsetDateTime.now();
        ZoneOffset offset = now.getOffset();
        openLongOrder("qn");
        // impl 同源规则：仅生成基准时点之后的当日时点（qd=08:00 单时点）
        boolean expectedRemaining =
                LocalDate.now().atTime(LocalTime.of(20, 0)).atOffset(offset).isAfter(now);
        // 重新读取（openLongOrder 已写入 qdOrderNo）
        int todayRows = planCountOn(qdOrderNo, "PENDING", LocalDate.now());
        int tomorrowRows = planCountOn(qdOrderNo, "PENDING", LocalDate.now().plusDays(1));
        if (expectedRemaining) {
            assertThat(todayRows).as("20:00 未到应补生成当日剩余时点一行").isEqualTo(1);
        } else {
            assertThat(todayRows).as("20:00 已过当日无剩余时点应零行").isZero();
        }
        assertThat(tomorrowRows).as("补偿面不生成次日计划（日切面承载）").isZero();
    }

    @Test
    @Order(5)
    @DisplayName("停嘱联动：bid 医嘱 STOPPED→次日 PENDING 计划全 CANCELLED（2 行）")
    void stopOrderCancelsNextDayPlans() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        ObjectNode stop = objectMapper.createObjectNode().put("reason", "IT 验收：停嘱联动");
        assertThat(postForEntity("/api/v1/inpatient/orders/" + bidOrderNo + "/stop", doctorToken, stop)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(planTimesOn(bidOrderNo, "PENDING", tomorrow))
                .as("停嘱后 PENDING 计划清零")
                .isEmpty();
        assertThat(planCountOn(bidOrderNo, "CANCELLED", LocalDate.now().plusDays(1)))
                .as("次日两行计划应全部作废")
                .isEqualTo(2);
    }
}

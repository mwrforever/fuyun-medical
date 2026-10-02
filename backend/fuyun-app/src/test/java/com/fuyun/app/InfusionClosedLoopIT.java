package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.inpatient.service.IOrderPlanService;
import com.fuyun.integration.api.ConsumerQueueSpec;
import com.fuyun.integration.api.MessagingGovernance;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.DeviceAccessMode;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * PR-3 验收锚点②（输液闭环全链，真栈 HTTP/MQ/DB/WS 零 mock）：PIVAS 摆药计划五步→病区签收
 * （dispense.completed 发布）→nursing 执行单批量 SIGNED+INFUSION 升格+监测建链→袋签核对→
 * 开始输注（infusion.started 发布→iot 监测关联消费）→模拟 iot.alarm.triggered 信封直投→执行单
 * 升级挂单 escalation_count=1（挂单不新建：任务清单零新增）→拔针（infusion.completed→iot 泵类
 * 解绑停监测+io_record INFUSION_AUTO 自动入量+M04 计划回签 EXECUTED）。
 *
 * <p><b>链路形态</b>：住院 PIVAS 医嘱全真链（开立→审方→转抄→次日计划→摆药五步——
 * InpatientOrderFlowIT/PharmacyDispenseGuardIT 先例逐段组装）；开始输注后 infusion.started 由
 * iot 消费（NursingInfusionStartedListener 纯读关联留痕——断言锚=幂等台账 received_event
 * PROCESSED 行）；iot.alarm.triggered 上游帧经 RabbitTemplate+EventEnvelopeCodec 手工合成直投
 * （PharmacyDispenseGuardIT publishCharged 同款 stub 边界）；拔针后 iot 停监测断言=泵类绑定
 * 解绑迁移（BOUND→UNBOUND 库态终局）。
 *
 * <p>容器三件套类级独占（GC9 红线，InpatientOrderFlowIT :74-88 逐字同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InfusionClosedLoopIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（流水键/幂等前置键载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（摆药/输液事件族消费链载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 输液链项目码与药品码共码（billing 建项定价 + pharmacy 药品建档对照） */
    private static final String IV_DRUG_CODE = "IT-IV-DRUG-001";

    /** 本 IT 病区与床位（SQL 直插主数据） */
    private static final String WARD_ID = "W-IT-9008";

    private static final long BED_ID = 920811L;

    private static final long PATIENT_ID = 920801L;

    /** V704 演示医师（开单操作者主体） */
    private static final String DOCTOR_LOGIN_NAME = "doctordemo";

    /** 输液泵设备号（iot 停监测解绑断言锚） */
    private static final String PUMP_DEVICE_ID = "it-iv-pump-001";

    /** 输液监测关联的 iot 病区（设备/绑定档案归属） */
    private static final long IOT_WARD_ID = 1001L;

    /** 拔针实际输注量（ml——io_record 自动入量断言值） */
    private static final int ACTUAL_VOLUME_ML = 250;

    /** MQ 消费链路等待上限 */
    private static final Duration LINK_TIMEOUT = Duration.ofSeconds(15);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 跨用例链路状态（JUnit 每用例新实例，业务号经 static 传递） */
    private static String adminToken = "";

    private static String reviewerToken = "";

    private static String doctorToken = "";

    private static String visitId = "";

    private static String orderNo = "";

    private static String planNo = "";

    /** 长期计划执行单号（start→拔针驱动载体，m04_plan_no 非空） */
    private static String planExecutionNo = "";

    /** 日切分解服务（次日计划预置——order-plan.generated 事件源） */
    @Autowired
    private IOrderPlanService orderPlanService;

    /** 设备档案 mapper：输液泵种子直插 */
    @Autowired
    private IotDeviceMapper deviceMapper;

    /** 绑定 mapper：BOUND 泵绑定种子直插（停监测解绑断言锚） */
    @Autowired
    private IotBindingMapper bindingMapper;

    /** MQ 发送模板：告警上游帧注入通道 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 信封编解码器：手工合成告警上游帧 */
    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /**
     * 捕获队列声明（A.5-4 治理红线：经 MessagingGovernance 声明，"it" 消费者模块形态——
     * IotLinkageFlowIT ItCaptureConfig 同款多队列合并）：dispense.completed（id 28）与
     * infusion.started/completed（id 62/63）各一队列。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_DISPENSE_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED;

        static final String Q_INFUSION_STARTED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_INFUSION_STARTED;

        static final String Q_INFUSION_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_INFUSION_COMPLETED;

        static final List<EventEnvelope> DISPENSE_COMPLETED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> INFUSION_STARTED = new CopyOnWriteArrayList<>();

        static final List<EventEnvelope> INFUSION_COMPLETED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itInfusionCaptureQueues(MessagingGovernance governance) {
            List<Declarable> declared = new ArrayList<>();
            declared.addAll(governance
                    .declareConsumerQueue(
                            new ConsumerQueueSpec("it", PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_INFUSION_STARTED))
                    .getDeclarables());
            declared.addAll(governance
                    .declareConsumerQueue(
                            new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_INFUSION_COMPLETED))
                    .getDeclarables());
            return new Declarables(declared);
        }

        @Bean
        ItInfusionCaptureListener itInfusionCaptureListener(EventEnvelopeCodec codec) {
            return new ItInfusionCaptureListener(codec);
        }
    }

    /** 捕获监听器本体（按事件类型分列表收帧，不登记 received_event）。 */
    static class ItInfusionCaptureListener {

        private final EventEnvelopeCodec codec;

        ItInfusionCaptureListener(EventEnvelopeCodec codec) {
            this.codec = codec;
        }

        /** @param message 原始帧 */
        @RabbitListener(
                queues = {
                    ItCaptureConfig.Q_DISPENSE_COMPLETED,
                    ItCaptureConfig.Q_INFUSION_STARTED,
                    ItCaptureConfig.Q_INFUSION_COMPLETED
                })
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.DISPENSE_COMPLETED.add(envelope);
            } else if (NursingMessagingConstants.EVENT_INFUSION_STARTED.equals(envelope.eventType())) {
                ItCaptureConfig.INFUSION_STARTED.add(envelope);
            } else if (NursingMessagingConstants.EVENT_INFUSION_COMPLETED.equals(envelope.eventType())) {
                ItCaptureConfig.INFUSION_COMPLETED.add(envelope);
            }
        }
    }

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

    /** 建项目+定价+发布一步到位（OutpatientFullFlowIT 同型三步链）。 */
    private void newItemWithPrice(String itemCode, long priceFen) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("itemCode", itemCode)
                .put("itemName", "IT 输液链项目 " + itemCode)
                .put("itemClass", "TREATMENT")
                .put("unit", "支")
                .put("comboFlag", false)
                .put("feeCategory", "DRUG_FEE");
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

    /** 药品建档（itemCode 与收费项目共码满足可计费开方守卫；静脉途径承载）。 */
    private long createDrug(String drugCode) {
        ObjectNode drug = objectMapper.createObjectNode();
        drug.put("drugCode", drugCode)
                .put("genericName", "IT 输液链药品 " + drugCode)
                .put("dosageForm", "注射液")
                .put("specification", "250ml:1g")
                .put("unit", "支")
                .putArray("routeCodes")
                .add("IV");
        drug.put("essentialFlag", false)
                .put("antibioClass", "NONE")
                .put("hazardLevel", "NONE")
                .put("skinTestFlag", false)
                .put("narcoticClass", "NORMAL")
                .put("itemCode", drugCode);
        return postJson("/api/v1/pharmacy/drugs", adminToken, drug).path("id").asLong();
    }

    /** 批次 SQL 直插（V703 零数据行——IT 造数红线偏差⑧先例口径；id 自带——MP ASSIGN_ID 应用层
     * 雪花无库端默认，jdbcTemplate 直插须显式主键，PharmacyDispenseGuardIT 同型）。 */
    private void insertBatch(long batchId, long drugId, String batchNo, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO pharmacy.drug_batch (id, drug_id, storehouse, batch_no, production_date,"
                        + " expire_date, quantity, locked_qty, status)"
                        + " VALUES (?, ?, 'OUTP_PHARM', ?, ?, ?, ?, 0, 'IN_STOCK')",
                batchId,
                drugId,
                batchNo,
                java.sql.Date.valueOf("2025-06-01"),
                java.sql.Date.valueOf("2027-06-01"),
                quantity);
    }

    /** 轮询等待医嘱到达目标状态（审方回执驱动异步收敛，禁盲等——InpatientOrderFlowIT 同型）。 */
    private void awaitOrderStatus(String orderNoText, String expected) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(jdbcTemplate.queryForObject(
                    "SELECT status FROM inpatient.medical_order WHERE order_no = ?", String.class, orderNoText))) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("医嘱状态迁移超时：orderNo=" + orderNoText + "，期望 " + expected);
    }

    /** 轮询等待该医嘱执行单行集到达目标行数（转抄/计划消费异步收敛）。 */
    private void awaitExecutionRows(int expected) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND deleted = 0",
                    Integer.class,
                    orderNo);
            if (n != null && n >= expected) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("执行单行集未到位：orderNo=" + orderNo + "，期望 ≥" + expected);
    }

    /** 该医嘱执行单行总数（签收/升格全量断言的分母）。 */
    private int totalRows() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND deleted = 0",
                Integer.class,
                orderNo);
        return n == null ? 0 : n;
    }

    /** 等待病区患者投影行在区（生成执行单与拔针自动入量的前置）。 */
    private void awaitProjection() {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM nursing.nursing_ward_patient WHERE visit_id = ? AND deleted = 0",
                    Integer.class,
                    visitId);
            if (n != null && n >= 1) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("投影行未在区：visitId=" + visitId);
    }

    /** 轮询等待捕获列表出现指定前缀帧。 */
    private EventEnvelope awaitCaptured(List<EventEnvelope> captured, String eventType) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            EventEnvelope hit = captured.stream()
                    .filter(e -> eventType.equals(e.eventType()))
                    .findFirst()
                    .orElse(null);
            if (hit != null) {
                return hit;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("捕获队列未收到帧：" + eventType);
    }

    /** 轮询等待业务条件成立（消费链异步收敛）。 */
    private void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + LINK_TIMEOUT.toMillis();
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

    @Test
    @Order(1)
    @DisplayName("前置：三账号/定价/药品批次/患者床位/入院链四步至 ADMITTED+泵设备绑定种子")
    void prepareStackAndAdmitPatient() {
        adminToken = loginToken(ADMIN_LOGIN_NAME);
        seedReviewerUser();
        reviewerToken = loginToken(REVIEWER_LOGIN_NAME);
        doctorToken = loginToken(DOCTOR_LOGIN_NAME);
        newItemWithPrice(IV_DRUG_CODE, 5000);
        long drugId = createDrug(IV_DRUG_CODE);
        insertBatch(910811L, drugId, "B-IT-IV-A", 10);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 输液链患者");
        jdbcTemplate.update(
                "INSERT INTO inpatient.bed (id, bed_no, ward_id, bed_attr, allow_gender, visit_id, status)"
                        + " VALUES (?, 'IT9-81', ?, 'NORMAL', NULL, NULL, 'FREE')",
                BED_ID,
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
                // 期望入住日按北京钟面取当日（时区红线）
                .put("expectDate", LocalDate.now(TimeConstants.HEALTHCARE_TZ).toString());
        postForEntity("/api/v1/inpatient/admissions/" + admissionNo + "/schedule", adminToken, schedule);
        visitId = postJson(
                        "/api/v1/inpatient/admissions/" + admissionNo + "/register",
                        adminToken,
                        objectMapper.createObjectNode().put("insuranceType", "IT-YIBAO"))
                .path("visitId")
                .asText();
        ObjectNode admit = objectMapper.createObjectNode();
        admit.put("wardId", WARD_ID).put("bedId", BED_ID).put("nursingLevel", "NORMAL");
        assertThat(postForEntity("/api/v1/inpatient/visits/" + visitId + "/admit-ward", adminToken, admit)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitProjection();

        // 输液泵设备与 BOUND 绑定种子（iot 停监测解绑断言锚——拔针后泵类绑定迁移 UNBOUND）
        IotDeviceEntity pump = new IotDeviceEntity();
        pump.setDeviceId(PUMP_DEVICE_ID);
        pump.setDeviceName("IT 输液链泵设备");
        pump.setDeviceType("INFUSION_PUMP");
        pump.setAccessMode(DeviceAccessMode.A);
        pump.setWardId(IOT_WARD_ID);
        pump.setStatus(DeviceStatus.ONLINE);
        assertThat(deviceMapper.insert(pump)).as("泵设备种子插入成功").isEqualTo(1);
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(PUMP_DEVICE_ID);
        binding.setPatientId(PATIENT_ID);
        binding.setVisitId(visitId);
        binding.setBedId(BED_ID);
        binding.setWardId(IOT_WARD_ID);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        assertThat(bindingMapper.insert(binding)).as("BOUND 泵绑定种子插入成功").isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("PIVAS 医嘱链：开立静脉长期医嘱→审方通过→转抄→次日计划→执行单两行（快照+计划）")
    void openIvLongOrderAndGenerateExecutions() throws Exception {
        // 开立 DRUG 长期医嘱（freqCode=qd，route=静脉滴注——PIVAS 判定锚）
        ObjectNode order = objectMapper.createObjectNode();
        order.put("orderType", "DRUG").put("orderClass", "LONG").put("freqCode", "qd");
        ObjectNode line = order.putArray("items").addObject();
        line.put("itemType", "DRUG")
                .put("itemCode", IV_DRUG_CODE)
                .put("itemName", "IT 输液链药品")
                .put("quantity", "1")
                .put("dosage", "0.5")
                .put("dosageUnit", "g")
                .put("route", "静脉滴注");
        JsonNode vo = postJson("/api/v1/inpatient/visits/" + visitId + "/orders", doctorToken, order);
        orderNo = vo.path("orderNo").asText();
        assertThat(vo.path("status").asText()).isEqualTo("CREATED");

        // 审方通过（工作台轮询取任务 id→reviewer 审批位）
        String taskId = null;
        for (int i = 0; i < 100; i++) {
            JsonNode list = getJson("/api/v1/pharmacy/review-tasks?status=PENDING&page=0&size=50", adminToken);
            for (JsonNode row : list.path("content")) {
                if (orderNo.equals(row.path("m04OrderNo").asText())) {
                    taskId = row.path("id").asText();
                    break;
                }
            }
            if (taskId != null) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        assertThat(taskId).as("医嘱 %s 的待审任务应在工作台", orderNo).isNotNull();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/review-tasks/" + taskId + "/approve",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 审方回执驱动 CREATED→AUDITED 异步收敛（禁盲取状态——转抄仅 AUDITED 态可入）
        awaitOrderStatus(orderNo, "AUDITED");

        // 转抄核对放行→TRANSFERRED→nursing 快照执行单落位
        ObjectNode check = objectMapper.createObjectNode();
        check.putArray("orderNos").add(orderNo);
        check.put("transferNurseId", "IT-NURSE-1").put("conclusion", "PASSED").put("secondCheckerId", "IT-NURSE-2");
        assertThat(postForEntity("/api/v1/inpatient/orders/transfer-check", adminToken, check)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        awaitExecutionRows(1);

        // 次日计划预置（日切服务直调等价 02:30 任务面）→order-plan.generated→nursing 计划执行单；
        // 转抄链 compensateToday 可能另生成当日剩余时点行（北京钟面早间窗）——取计划时点最晚行
        // （次日 08:00 行恒在）为驱动载体，行数断言按实际总数承载
        int created = orderPlanService.decomposeNextDay(
                LocalDate.now(TimeConstants.HEALTHCARE_TZ).plusDays(1));
        assertThat(created).as("长期 qd 次日计划应生成一行").isEqualTo(1);
        awaitExecutionRows(2);
        Map<String, Object> planRow = jdbcTemplate.queryForMap(
                "SELECT execution_no, m04_plan_no FROM nursing.order_execution"
                        + " WHERE m04_order_no = ? AND m04_plan_no IS NOT NULL AND deleted = 0"
                        + " ORDER BY plan_time DESC LIMIT 1",
                orderNo);
        planExecutionNo = (String) planRow.get("execution_no");
        planNo = (String) planRow.get("m04_plan_no");
        assertThat(planNo).as("计划号契约 PL+yyyyMMdd+5 位流水").matches("PL\\d{13}");
    }

    @Test
    @Order(3)
    @DisplayName("摆药五步：PIVAS 计划生成→pick→verify（异人双签）→issue→deliver→receive→dispense.completed 帧")
    void dispensePlanFiveStepsToCompleted() {
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("m04OrderNo", orderNo).put("wardId", WARD_ID);
        JsonNode plans = postJson("/api/v1/pharmacy/dispense-plans/generate", adminToken, generate);
        assertThat(plans.isArray()).as("生成应返回计划清单").isTrue();
        assertThat(plans.size()).as("qd 次日一计划").isEqualTo(1);
        String dispensePlanNo = plans.get(0).path("planNo").asText();
        assertThat(plans.get(0).path("planType").asText()).as("静脉用法→PIVAS 判定").isEqualTo("PIVAS");

        // 五步链：pick（admin）→verify（reviewer 异人双签）→issue→deliver→receive
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/pick",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/verify",
                                reviewerToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/issue",
                                adminToken,
                                objectMapper.createObjectNode())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/deliver",
                                adminToken,
                                objectMapper.createObjectNode().put("carrier", "IT-配送员"))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // deliver 半步不迁状态（Task 8 裁决：仅 issued_at 时间线承载）
        String statusAfterDeliver = jdbcTemplate.queryForObject(
                "SELECT status FROM pharmacy.dispense_plan WHERE plan_no = ?", String.class, dispensePlanNo);
        assertThat(statusAfterDeliver).as("配送交接保持 CHECKED（签收归 receive 迁移）").isEqualTo("CHECKED");
        assertThat(postForEntity(
                                "/api/v1/pharmacy/dispense-plans/" + dispensePlanNo + "/receive",
                                adminToken,
                                objectMapper.createObjectNode().put("receivedBy", 5))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 住院四字段载荷帧断言（V1111 扩列）
        EventEnvelope completed =
                awaitCaptured(ItCaptureConfig.DISPENSE_COMPLETED, PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED);
        assertThat(completed.payload().path("m04OrderNo").asText()).isEqualTo(orderNo);
        assertThat(completed.payload().path("dispenseType").asText())
                .as("PIVAS 链类型")
                .isEqualTo("INPATIENT_PIVA");
        assertThat(completed.payload().path("dispensePlanNo").asText()).isEqualTo(dispensePlanNo);
        assertThat(completed.payload().path("lines").isArray()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("签收衔接：执行单批量 SIGNED+INFUSION 升格+监测建链（袋签码=行摘要回退推导）")
    void signoffDrivesInfusionUpgradeAndLink() {
        // 行数按实际总数承载（转抄链当日剩余时点行使总行数在北京早间窗为 3，其余窗为 2）
        awaitUntil(
                "执行单全量 SIGNED",
                () -> totalRows()
                        == jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND status = 'SIGNED' AND deleted = 0",
                                Integer.class,
                                orderNo));
        awaitUntil(
                "PIVAS 升格 INFUSION（全量）",
                () -> totalRows()
                        == jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ?"
                                        + " AND execution_type = 'INFUSION' AND deleted = 0",
                                Integer.class,
                                orderNo));
        awaitUntil(
                "监测建链（计划执行单挂接）",
                () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM nursing.infusion_monitor_link WHERE execution_no = ?"
                                        + " AND link_status = 'MONITORING' AND deleted = 0",
                                Integer.class,
                                planExecutionNo)
                        == 1);
        Map<String, Object> link = jdbcTemplate.queryForMap(
                "SELECT bag_label_code FROM nursing.infusion_monitor_link WHERE execution_no = ? AND deleted = 0",
                planExecutionNo);
        assertThat((String) link.get("bag_label_code"))
                .as("袋签码=行摘要回退（追溯码空集时 itemCode#batchNo）")
                .contains(IV_DRUG_CODE);
    }

    @Test
    @Order(5)
    @DisplayName("开始输注：袋签核对 PASS→start（破码时间窗）→EXECUTING+infusion.started 帧+iot 监测关联消费")
    void startInfusionPublishesAndIotConsumes() {
        String bagLabelCode = jdbcTemplate.queryForObject(
                "SELECT bag_label_code FROM nursing.infusion_monitor_link WHERE execution_no = ? AND deleted = 0",
                String.class,
                planExecutionNo);
        // 袋签三向核对（BAG_LABEL 维：挂接袋签码匹配）→CHECKED
        ObjectNode checkReq = objectMapper.createObjectNode();
        checkReq.put("code", bagLabelCode).put("codeType", "BAG_LABEL");
        assertThat(postForEntity("/api/v1/nursing/executions/" + planExecutionNo + "/check", adminToken, checkReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        // 开始输注（次日计划时点窗外——口头医嘱现场确认面 overrideTimeWindow 承载）
        ObjectNode startReq = objectMapper.createObjectNode();
        startReq.put("executorId", 66).put("deviceId", PUMP_DEVICE_ID).put("overrideTimeWindow", true);
        assertThat(postForEntity("/api/v1/nursing/executions/" + planExecutionNo + "/start", adminToken, startReq)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM nursing.order_execution WHERE execution_no = ?", String.class, planExecutionNo);
        assertThat(status).as("开始输注后执行中").isEqualTo("EXECUTING");

        // infusion.started 帧断言（id 62 五字段契约）
        EventEnvelope started =
                awaitCaptured(ItCaptureConfig.INFUSION_STARTED, NursingMessagingConstants.EVENT_INFUSION_STARTED);
        assertThat(started.payload().path("executionNo").asText()).isEqualTo(planExecutionNo);
        assertThat(started.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);
        assertThat(started.payload().path("visitId").asText()).isEqualTo(visitId);
        assertThat(started.payload().path("bagLabelCode").asText()).isEqualTo(bagLabelCode);

        // iot 监测关联断言：NursingInfusionStartedListener 纯读消费——幂等台账 PROCESSED 行实证
        awaitUntil(
                "iot 消费 infusion.started（监测关联建立留痕）",
                () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM integration.received_event WHERE event_type = ?"
                                        + " AND consumer_module = 'iot' AND status = 'PROCESSED'",
                                Integer.class,
                                NursingMessagingConstants.EVENT_INFUSION_STARTED)
                        >= 1);
    }

    @Test
    @Order(6)
    @DisplayName("告警升级挂单：iot.alarm.triggered 信封直投→escalation_count=1（挂单不新建任务）")
    void alarmTriggeredEscalatesExecutionWithoutNewTask() {
        int tasksBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_task WHERE deleted = 0", Integer.class);
        String alarmNo = "AL-IT-IV-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("alarmNo", alarmNo)
                .put("deviceId", PUMP_DEVICE_ID)
                .put("patientId", PATIENT_ID)
                .put("visitId", visitId)
                .put("wardId", IOT_WARD_ID)
                .put("alarmLevel", "CRITICAL")
                .put("metricCode", "vital.infusion-rate");
        rabbitTemplate.convertAndSend(
                "fy.topic",
                IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "iot",
                        IotMessagingConstants.EVENT_ALARM_TRIGGERED,
                        "it-iv-alarm",
                        objectMapper.convertValue(payload, Map.class)));

        awaitUntil("执行单升级挂单（escalation_count=1+告警锚）", () -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT escalation_count, latest_alarm_no FROM nursing.order_execution" + " WHERE execution_no = ?",
                    planExecutionNo);
            return Integer.valueOf(1).equals(((Number) row.get("escalation_count")).intValue())
                    && alarmNo.equals(row.get("latest_alarm_no"));
        });
        // 挂单不新建纪律：任务清单零新增
        int tasksAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.nursing_task WHERE deleted = 0", Integer.class);
        assertThat(tasksAfter).as("告警升级挂单不得新建护理任务").isEqualTo(tasksBefore);
    }

    @Test
    @Order(7)
    @DisplayName("拔针收口：needle-out→COMPLETED+挂接 ENDED+自动入量+infusion.completed+泵解绑+M04 回签")
    void needleOutCompletesClosedLoop() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("executorId", 66).put("actualVolumeMl", ACTUAL_VOLUME_ML).put("wristbandCode", visitId);
        assertThat(postForEntity("/api/v1/nursing/executions/" + planExecutionNo + "/needle-out", adminToken, req)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        // 执行单终态与挂接收口
        awaitUntil("执行单 COMPLETED", () -> "COMPLETED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        planExecutionNo)));
        awaitUntil("监测挂接收口 ENDED", () -> "ENDED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT link_status FROM nursing.infusion_monitor_link WHERE execution_no = ?"
                                + " AND deleted = 0",
                        String.class,
                        planExecutionNo)));

        // 自动入量行（INFUSION_AUTO/IV_FLUID/250ml——V804 source_ref 列首个落值面）
        Map<String, Object> intake = jdbcTemplate.queryForMap(
                "SELECT source, item_code, quantity, source_ref FROM nursing.io_record"
                        + " WHERE source_ref = ? AND deleted = 0",
                planExecutionNo);
        assertThat(intake.get("source")).as("自动入量来源").isEqualTo("INFUSION_AUTO");
        assertThat(intake.get("item_code")).as("入量项目=静脉输液").isEqualTo("IV_FLUID");
        assertThat(((java.math.BigDecimal) intake.get("quantity")).intValue())
                .as("入量=实际输注量")
                .isEqualTo(ACTUAL_VOLUME_ML);

        // infusion.completed 帧断言（id 63 患者维度契约）
        EventEnvelope completed =
                awaitCaptured(ItCaptureConfig.INFUSION_COMPLETED, NursingMessagingConstants.EVENT_INFUSION_COMPLETED);
        assertThat(completed.payload().path("executionNo").asText()).isEqualTo(planExecutionNo);
        assertThat(completed.payload().path("patientId").asLong()).isEqualTo(PATIENT_ID);

        // iot 停监测断言：泵类绑定解绑迁移（BOUND→UNBOUND 库态终局）
        awaitUntil("泵类绑定解绑（停监测）", () -> !"BOUND"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM iot.iot_binding WHERE device_id = ? ORDER BY id DESC LIMIT 1",
                        String.class,
                        PUMP_DEVICE_ID)));

        // M04 回签：计划 EXECUTED+对账 CONFIRMED（双路回签主路径）
        awaitUntil("M04 计划回签 EXECUTED", () -> "EXECUTED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM inpatient.order_execute_plan WHERE plan_no = ?", String.class, planNo)));
        awaitUntil("回签对账 CONFIRMED", () -> "CONFIRMED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT confirm_status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        planExecutionNo)));
    }
}

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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
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
 * PR-6 M05 验收锚点②：文书链 + 评估链 + 交接班链真栈 IT（FU-M05-03 等，零 mock）。链路：
 * 入区登记夹具 → 护理记录创建/提交锁定（DRAFT→SUBMITTED 签名盖章）→ 修订留痕（原行零改动、
 * 新行 REVISED 链）→ 草稿修订拒（NS-1008）→ 出入量明细×4 与班次小结（聚合勾稽 + 体温单
 * DAILY_VALUE 条目 + 同周期幂等）→ MORSE 高危评估（自动判级 HIGH + 防范任务生成 + 床旁
 * FALL 风险标识）→ 交接班生成（SBAR 汇总 + 待续事项含防范任务）与完成（双签 + 事件发布）→
 * 逾期任务读时惰性标记（CAS 单次递增幂等）。
 *
 * <p>偏差登记（简报 vs 实况，详见 task-11-report）：①简报路径 /records——实况
 * /api/v1/nursing/nursing-records（NursingRecordController 冻结端点面）。②简报 step4
 * 「period 覆盖上述时间」入参——实况 IoSummaryCreateRequest 无 period 组件，SHIFT 周期由
 * 病区班次定义（W01 种子 DAY/EVENING/NIGHT 三班全覆盖 24h）推导；明细 occur_at 为服务器时间，
 * 故班次按明细行实际发生时点动态选择（简报硬编码 DAY 仅日间窗口成立，夜间运行会漏聚合）。
 * ③患者主档行经 jdbcTemplate 直插（resolve 依赖，OutpatientFullFlowIT:422 同款主数据夹具）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NursingDocumentFlowIT extends FuyunStackITBase {

    /** 类级独占三容器（GC9 红线：容器禁收敛入基类——各 IT 独占一套，防捕获队列串扰） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（NR/AS/HO/TK 业务号发号器原子自增键） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（nursing.shift.completed 真实发布链；本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 文书链患者主索引（patient.patient SQL 直插主数据夹具） */
    private static final long PATIENT_ID = 921051L;

    /** 文书链住院就诊号（I 型 14 位） */
    private static final String VISIT_ID = "I2026092200011";

    /** 逾期任务夹具行 id（状态机边界夹具：逾期任务行经 jdbcTemplate 构造，批复口径允许项） */
    private static final long OVERDUE_TASK_ID = 921061L;

    /** 逾期任务夹具业务号（uk_nursing_task_no 唯一，禁与发号器 TK 段冲突的固定字面量） */
    private static final String OVERDUE_TASK_NO = "TK-IT-OVERDUE-921061";

    /** 跨用例链路状态（业务号锚经 static 传递） */
    private static String token = "";

    private static String recordNo = "";

    private static String revisedNo = "";

    private static String preventionTaskNo = "";

    private static String handoverNo = "";

    /**
     * 捕获队列声明（A.5-4 治理红线：经 MessagingGovernance 声明，OutpatientFullFlowIT:148-155
     * 的 "it" 消费者模块形态）。队列 q.it.nursing.shift.completed（V800 id 60 登记行）。
     */
    @TestConfiguration
    static class ItCaptureConfig {

        static final String Q_SHIFT_COMPLETED =
                MessagingConstants.QUEUE_PREFIX + "it." + NursingMessagingConstants.EVENT_SHIFT_COMPLETED;

        static final CountDownLatch LATCH = new CountDownLatch(1);

        static final List<EventEnvelope> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        Declarables itShiftCompletedCaptureQueue(MessagingGovernance governance) {
            return governance.declareConsumerQueue(
                    new ConsumerQueueSpec("it", NursingMessagingConstants.EVENT_SHIFT_COMPLETED));
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
        @RabbitListener(queues = {ItCaptureConfig.Q_SHIFT_COMPLETED})
        void onMessage(Message message) {
            EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
            if (NursingMessagingConstants.EVENT_SHIFT_COMPLETED.equals(envelope.eventType())) {
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

    /** 无体 POST 助手（submit 等动作端点的状态码断言面）。 */
    private ResponseEntity<String> postEmpty(String path, String authToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        return restTemplate.postForEntity(path, new HttpEntity<>(headers), String.class);
    }

    /** 入区登记夹具（真实流转：POST /ward-patients；一览相关验收不走直插）。 */
    private void registerWardPatient() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID)
                .put("patientId", PATIENT_ID)
                .put("wardId", "W01")
                .put("bedNo", "01")
                .put("patientName", "IT 文书患者")
                .put("nursingLevel", "NORMAL");
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/ward-patients", token, req);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("入区登记夹具应 2xx，实况：%s", resp.getBody())
                .isTrue();
    }

    /** 出入量明细录入（visitId + 类型/项目/数量三参）。 */
    private void postIoRecord(String ioType, String itemCode, String quantity) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID)
                .put("ioType", ioType)
                .put("itemCode", itemCode)
                .put("quantity", quantity);
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/io-records", token, req);
        assertThat(resp.getStatusCode().value())
                .as("出入量明细应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
    }

    /**
     * 按明细行实际发生时点选择班次（W01 种子三班全覆盖 24h：NIGHT 00:00-08:00 / DAY 08:00-16:00 /
     * EVENING 16:00-24:00）。occur_at 为服务器时间（GC25 禁入参），按末笔明细时点取班次可使
     * 班次窗口确定覆盖全部明细，消除简报硬编码 DAY 的夜间运行漏聚合窗口。
     */
    private String shiftCodeOfLastIoRecord() {
        Timestamp lastOccurAt = jdbcTemplate.queryForObject(
                "SELECT max(occur_at) FROM nursing.io_record WHERE visit_id = ?", Timestamp.class, VISIT_ID);
        LocalTime time = lastOccurAt.toLocalDateTime().toLocalTime();
        if (time.isBefore(LocalTime.of(8, 0))) {
            return "NIGHT";
        }
        return time.isBefore(LocalTime.of(16, 0)) ? "DAY" : "EVENING";
    }

    @Test
    @Order(1)
    @DisplayName("护理记录创建与提交：CRITICAL 类三段落 DRAFT，submit 后 SUBMITTED 且签名留痕")
    void step1_createAndSubmitNursingRecord() {
        token = loginToken(ADMIN_LOGIN_NAME);
        jdbcTemplate.update(
                "INSERT INTO patient.patient (patient_id, name, sex, status, register_channel)"
                        + " VALUES (?, ?, '1', 'NORMAL', 'WINDOW')",
                PATIENT_ID,
                "IT 文书患者");
        registerWardPatient();
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID)
                .put("recordClass", "CRITICAL")
                .put("observation", "病情观察：神志清楚，精神可")
                .put("measures", "护理措施：心电监护，每小时巡视")
                .put("evaluation", "效果评价：生命体征平稳");
        ResponseEntity<String> created = postForEntity("/api/v1/nursing/nursing-records", token, req);
        assertThat(created.getStatusCode().value())
                .as("创建草稿应 200，实况：%s", created.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(created.getBody());
        assertThat(vo.path("status").asText()).isEqualTo("DRAFT");
        assertThat(vo.path("recordClass").asText()).isEqualTo("CRITICAL");
        recordNo = vo.path("recordNo").asText();
        assertThat(recordNo).as("记录号契约 NR+yyyyMMdd+5 位流水").matches("NR\\d{13}");
        // 提交锁定：DRAFT→SUBMITTED，签名操作者盖章
        ResponseEntity<String> submitted = postEmpty("/api/v1/nursing/nursing-records/" + recordNo + "/submit", token);
        assertThat(submitted.getStatusCode().value()).as("提交应 200").isEqualTo(200);
        JsonNode submittedVo = toNode(submitted.getBody());
        assertThat(submittedVo.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submittedVo.path("signedOperator").asText()).as("提交应签名留痕").isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("已提交记录修订留痕：revise 允许且插 REVISED 新行（revised_from 链），原行仍 SUBMITTED 零改动")
    void step2_submittedRecordIsLocked() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("observation", "病情观察（修订）：神志清楚，精神可，夜间入睡可")
                .put("measures", "护理措施（修订）：心电监护，每两小时巡视")
                .put("evaluation", "效果评价（修订）：生命体征平稳");
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/nursing-records/" + recordNo + "/revise", token, req);
        assertThat(resp.getStatusCode().value())
                .as("已提交记录修订应放行（留痕语义），实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        assertThat(vo.path("status").asText()).isEqualTo("REVISED");
        assertThat(vo.path("revisedFrom").asText()).as("修订件应挂原号链").isEqualTo(recordNo);
        assertThat(vo.path("observation").asText()).contains("修订");
        revisedNo = vo.path("recordNo").asText();
        // 原行零改动：回读仍 SUBMITTED 且正文为原值（GC25 原值可见红线）
        JsonNode original = getJson("/api/v1/nursing/nursing-records/" + recordNo, token);
        assertThat(original.path("status").asText()).as("原行状态不得被修订改写").isEqualTo("SUBMITTED");
        assertThat(original.path("observation").asText()).as("原行正文零改动").isEqualTo("病情观察：神志清楚，精神可");
    }

    @Test
    @Order(3)
    @DisplayName("草稿修订拒：DRAFT 行 revise 409 NS-1008（仅 SUBMITTED 可发起修订）")
    void step3_reviseDraftRejected() {
        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("visitId", VISIT_ID).put("recordClass", "GENERAL").put("observation", "草稿观察");
        JsonNode draftVo = toNode(
                postForEntity("/api/v1/nursing/nursing-records", token, draft).getBody());
        String draftNo = draftVo.path("recordNo").asText();
        ObjectNode revise = objectMapper.createObjectNode();
        revise.put("observation", "对草稿的修订不应成立");
        ResponseEntity<String> resp =
                postForEntity("/api/v1/nursing/nursing-records/" + draftNo + "/revise", token, revise);
        assertThat(resp.getStatusCode().value()).as("草稿修订应 409").isEqualTo(409);
        assertThat(toNode(resp.getBody()).path("errorCode").asText()).isEqualTo("NS-1008");
    }

    @Test
    @Order(4)
    @DisplayName("出入量明细×4 与班次小结：入 1500/出 1100/平衡 400 勾稽，体温单 IO_SUMMARY_SHIFT 条目一行")
    void step4_ioRecordsAndShiftSummary() {
        postIoRecord("INTAKE", "IV_FLUID", "1000");
        postIoRecord("INTAKE", "ORAL", "500");
        postIoRecord("OUTPUT", "URINE", "800");
        postIoRecord("OUTPUT", "STOOL", "300");
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID).put("summaryType", "SHIFT").put("shiftCode", shiftCodeOfLastIoRecord());
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/io-summaries", token, req);
        assertThat(resp.getStatusCode().value())
                .as("班次小结应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        assertThat(vo.path("totalIntake").asText()).as("总入量=1000+500").isEqualTo("1500.00");
        assertThat(vo.path("totalOutput").asText()).as("总出量=800+300").isEqualTo("1100.00");
        assertThat(vo.path("balance").asText()).as("平衡=1500-1100").isEqualTo("400.00");
        // 体温单日行值条目恰一行（红双线渲染依据）
        Integer dailyEntries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry e"
                        + " JOIN nursing.temperature_chart_page p ON p.id = e.page_id"
                        + " WHERE p.visit_id = ? AND e.entry_type = 'DAILY_VALUE'"
                        + " AND e.daily_value_type = 'IO_SUMMARY_SHIFT'",
                Integer.class,
                VISIT_ID);
        assertThat(dailyEntries).as("班次小结应写恰一条 IO_SUMMARY_SHIFT 日行值").isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("小结幂等：同周期再次 POST 返回既有行（id 相同），io_summary 行数与条目数不变")
    void step5_ioSummaryIdempotent() {
        Integer summaryBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.io_summary WHERE visit_id = ?", Integer.class, VISIT_ID);
        Integer entryBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry e"
                        + " JOIN nursing.temperature_chart_page p ON p.id = e.page_id"
                        + " WHERE p.visit_id = ? AND e.entry_type = 'DAILY_VALUE'",
                Integer.class,
                VISIT_ID);
        ObjectNode first = objectMapper.createObjectNode();
        first.put("visitId", VISIT_ID).put("summaryType", "SHIFT").put("shiftCode", shiftCodeOfLastIoRecord());
        JsonNode firstVo = toNode(
                postForEntity("/api/v1/nursing/io-summaries", token, first).getBody());
        ObjectNode second = objectMapper.createObjectNode();
        second.put("visitId", VISIT_ID).put("summaryType", "SHIFT").put("shiftCode", shiftCodeOfLastIoRecord());
        JsonNode secondVo = toNode(
                postForEntity("/api/v1/nursing/io-summaries", token, second).getBody());
        assertThat(secondVo.path("id").asLong())
                .as("同周期重复小结应幂等返回既有行")
                .isEqualTo(firstVo.path("id").asLong());
        Integer summaryAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.io_summary WHERE visit_id = ?", Integer.class, VISIT_ID);
        Integer entryAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.temperature_chart_entry e"
                        + " JOIN nursing.temperature_chart_page p ON p.id = e.page_id"
                        + " WHERE p.visit_id = ? AND e.entry_type = 'DAILY_VALUE'",
                Integer.class,
                VISIT_ID);
        assertThat(summaryAfter).as("幂等命中不得新增小结行").isEqualTo(summaryBefore);
        assertThat(entryAfter).as("幂等命中不得新增日行值条目").isEqualTo(entryBefore);
    }

    @Test
    @Order(6)
    @DisplayName("MORSE 高危评估：总分 50 判级 HIGH，自动生成 PREVENTION 防范任务（PENDING）并回写 FALL 风险标识")
    void step6_highRiskAssessmentGeneratesPreventionTask() {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("visitId", VISIT_ID).put("scaleType", "MORSE");
        req.put("assessedAt", OffsetDateTime.now().toString());
        ObjectNode answers = req.putObject("answers");
        answers.put("FALL_HISTORY", 25).put("SECOND_DIAGNOSIS", 15).put("AMBULATORY_AID", 0);
        answers.put("IV_THERAPY", 10).put("GAIT", 0).put("MENTAL_STATUS", 0);
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/assessments", token, req);
        assertThat(resp.getStatusCode().value())
                .as("评估单创建应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        assertThat(vo.path("totalScore").asInt()).as("MORSE 总分=25+15+10").isEqualTo(50);
        assertThat(vo.path("riskLevel").asText()).as("≥45 应判高危").isEqualTo("HIGH");
        preventionTaskNo = vo.path("triggeredTaskRef").asText();
        assertThat(preventionTaskNo).as("高危评估应生成防范任务").isNotBlank();
        // 防范任务行锚：PREVENTION 类型、PENDING 态、来源评估联动
        var taskRow = jdbcTemplate.queryForMap(
                "SELECT task_type, status, source, source_ref FROM nursing.nursing_task WHERE task_no = ?",
                preventionTaskNo);
        assertThat(taskRow.get("task_type")).isEqualTo("PREVENTION");
        assertThat(taskRow.get("status")).isEqualTo("PENDING");
        assertThat(taskRow.get("source")).isEqualTo("ASSESSMENT");
        assertThat(taskRow.get("source_ref"))
                .as("任务应回挂评估单号")
                .isEqualTo(vo.path("assessNo").asText());
        // 床旁风险标识：MORSE 高危回写 FALL
        String riskFlags = jdbcTemplate.queryForObject(
                "SELECT risk_flags FROM nursing.nursing_ward_patient WHERE visit_id = ?", String.class, VISIT_ID);
        assertThat(riskFlags).as("MORSE 高危应回写跌倒风险标识").contains("FALL");
    }

    @Test
    @Order(7)
    @DisplayName("交接班生成与完成：SBAR 汇总 patientSummary.total≥1、pendingItems 含防范任务；complete 双签 COMPLETED")
    void step7_handoverGenerateAndComplete() {
        ObjectNode generate = objectMapper.createObjectNode();
        generate.put("wardId", "W01").put("shiftCode", "DAY");
        ResponseEntity<String> resp = postForEntity("/api/v1/nursing/handovers/generate", token, generate);
        assertThat(resp.getStatusCode().value())
                .as("交接班生成应 200，实况：%s", resp.getBody())
                .isEqualTo(200);
        JsonNode vo = toNode(resp.getBody());
        handoverNo = vo.path("handoverNo").asText();
        assertThat(handoverNo).as("交接班单号契约 HO+yyyyMMdd+5 位流水").matches("HO\\d{13}");
        assertThat(vo.path("status").asText()).isEqualTo("DRAFT");
        assertThat(vo.path("patientSummary").path("total").asInt())
                .as("在区患者应入汇总")
                .isGreaterThanOrEqualTo(1);
        boolean pendingHit = false;
        for (JsonNode item : vo.path("pendingItems")) {
            pendingHit =
                    pendingHit || preventionTaskNo.equals(item.path("taskNo").asText());
        }
        assertThat(pendingHit).as("待续事项应含 step6 防范任务").isTrue();
        // 完成：接班签名盖章（双签另一侧），DRAFT→COMPLETED
        ObjectNode complete = objectMapper.createObjectNode();
        complete.put("incomingNurseId", "2");
        ResponseEntity<String> done =
                postForEntity("/api/v1/nursing/handovers/" + handoverNo + "/complete", token, complete);
        assertThat(done.getStatusCode().value()).as("交接班完成应 200").isEqualTo(200);
        JsonNode doneVo = toNode(done.getBody());
        assertThat(doneVo.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(doneVo.path("incomingNurseId").asText()).isEqualTo("2");
        assertThat(doneVo.path("incomingSignedAt").asText()).as("接班签名时间应盖章").isNotBlank();
    }

    @Test
    @Order(8)
    @DisplayName("事件发布：nursing.shift.completed 经捕获队列可消费，载荷 handoverNo 与 step7 一致")
    void step8_shiftCompletedEventPublished() throws Exception {
        assertThat(ItCaptureConfig.LATCH.await(10, TimeUnit.SECONDS))
                .as("shift.completed 事件应可消费（≤10s）")
                .isTrue();
        EventEnvelope frame = ItCaptureConfig.CAPTURED.stream()
                .filter(e -> NursingMessagingConstants.EVENT_SHIFT_COMPLETED.equals(e.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(frame.producer()).isEqualTo(NursingMessagingConstants.MODULE);
        assertThat(frame.payload().path("handoverNo").asText()).isEqualTo(handoverNo);
        assertThat(frame.payload().path("wardId").asText()).isEqualTo("W01");
        assertThat(frame.payload().path("incomingNurseId").asText()).isEqualTo("2");
    }

    @Test
    @Order(9)
    @DisplayName("逾期任务读时惰性标记：-2h 计划任务首查 overdueFlag=true/escalationCount=1，再查不重复递增")
    void step9_taskOverdueLazyMarking() {
        // 状态机边界夹具：逾期任务行直插（plan_time 早于阈值 30 分钟以上）
        jdbcTemplate.update(
                "INSERT INTO nursing.nursing_task"
                        + " (id, task_no, patient_id, visit_id, ward_id, task_type, source, plan_time,"
                        + " priority, overdue_flag, escalation_count, status, created_by, updated_by)"
                        + " VALUES (?, ?, ?, ?, 'W01', 'TURN', 'MANUAL', now() - interval '2 hours',"
                        + " 'NORMAL', false, 0, 'PENDING', 'it-fixture', 'it-fixture')",
                OVERDUE_TASK_ID,
                OVERDUE_TASK_NO,
                PATIENT_ID,
                VISIT_ID);
        JsonNode first = getJson("/api/v1/nursing/tasks?wardId=W01&status=PENDING", token);
        JsonNode firstRow = findByTaskNo(first);
        assertThat(firstRow).as("病区在途清单应命中逾期夹具行").isNotNull();
        assertThat(firstRow.path("overdueFlag").asBoolean())
                .as("越阈值在途任务应被惰性标记逾期")
                .isTrue();
        assertThat(firstRow.path("escalationCount").asInt()).as("升级计数应单次递增为 1").isEqualTo(1);
        // 幂等：再次查询不得重复递增（overdue_flag=false 谓词仅首次递增）
        JsonNode second = getJson("/api/v1/nursing/tasks?wardId=W01&status=PENDING", token);
        assertThat(findByTaskNo(second).path("escalationCount").asInt())
                .as("重复查询不得重复递增")
                .isEqualTo(1);
    }

    /** 在任务清单出参中按业务号定位行（逾期惰性标记断言锚）。 */
    private JsonNode findByTaskNo(JsonNode list) {
        for (JsonNode item : list) {
            if (OVERDUE_TASK_NO.equals(item.path("taskNo").asText())) {
                return item;
            }
        }
        return null;
    }
}

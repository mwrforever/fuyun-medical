package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PR-3 验收锚点④（摆药签收衔接消费面，真栈 MQ 消费链）：pharmacy.dispense.completed 三语义——
 * 门诊旧载荷行（m04OrderNo 缺席）零副作用直接确认；住院行（V1111 四字段扩列）驱动执行单批量
 * SIGNED + PIVAS 升格 INFUSION + 监测建链；重复投递幂等（幂等台账 + CAS 谓词双层，零重复副作用）。
 *
 * <p><b>上游帧注入形态（stub 边界红线）</b>：门诊 charged 注入先例（PharmacyDispenseGuardIT
 * publishCharged）——IT 内一律 RabbitTemplate + EventEnvelopeCodec 手工合成信封直投 fy.topic，
 * 消费侧走真实 q.nursing.pharmacy.dispense.completed 监听器全链（幂等台账落库 + 业务执行），
 * 断言锚=DB 终态。执行单夹具行经 jdbcTemplate 直插（NursingVitalSignFlowIT 批准口径——消费面
 * 验收 IT 的状态边界夹具；全链真夹具归 NursingOrderExecutionFlowIT 承载，本 IT 聚焦消费语义）。
 *
 * <p>容器三件套类级独占（GC9 红线，PharmacyDispenseGuardIT :62-78 逐字同型）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispenseSignoffLinkageIT extends FuyunStackITBase {

    /** 类级独占三容器（tag 与 deploy compose 严格一致 + it/rabbitmq.conf 挂载） */
    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.2-pg16").asCompatibleSubstituteFor("postgres"));

    /** Redis 容器（消费幂等前置键载体） */
    @Container
    @ServiceConnection
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    /** RabbitMQ 容器（dispense.completed 消费链载体，本 IT 独占 broker） */
    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:4.3.5-management"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("it/rabbitmq.conf"), "/etc/rabbitmq/conf.d/20-fuyun-it.conf");

    /** 门诊旧载荷零副作用断言锚：哨兵医嘱号（不参与住院行签收面，状态不变即零副作用实证） */
    private static final String OUTPATIENT_SENTINEL_ORDER = "MO-IT-SIGN-OUTP";

    /** 住院行断言锚：医嘱号（两行夹具——快照行 + 计划行，批量签收与升格面） */
    private static final String INPATIENT_ORDER = "MO-IT-SIGN-INP";

    /** 住院行计划号（计划拆分行夹具锚，m04_plan_no 非空形态） */
    private static final String INPATIENT_PLAN = "PL-IT-SIGN-0001";

    /** 哨兵执行单号（门诊零副作用断言锚） */
    private static final String SENTINEL_EXECUTION = "EX-IT-SIGN-0001";

    /** 住院行快照执行单号（m04_plan_no NULL 形态） */
    private static final String SNAPSHOT_EXECUTION = "EX-IT-SIGN-0002";

    /** 住院行计划执行单号（m04_plan_no 非空形态） */
    private static final String PLAN_EXECUTION = "EX-IT-SIGN-0003";

    /** 夹具患者与就诊（投影/归属列 NOT NULL 承载值） */
    private static final long PATIENT_ID = 920701L;

    private static final String VISIT_ID = "I2026100300007";

    private static final String WARD_ID = "W-IT-9007";

    /** 直插主键常量（MP ASSIGN_ID 应用层雪花，jdbcTemplate 直插须自带 id；雪花段外种子保留段） */
    private static final long SENTINEL_ROW_ID = 920711L;

    private static final long SNAPSHOT_ROW_ID = 920712L;

    private static final long PLAN_ROW_ID = 920713L;

    /** 消费链路等待上限（MQ 消费异步收敛，10s 量级与既有 IT 同款） */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(10);

    /** DB 轮询步进 */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** MQ 发送模板：上游帧注入通道 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 信封编解码器：手工合成上游帧 */
    @Autowired
    private EventEnvelopeCodec envelopeCodec;

    /**
     * 夹具执行单行直插（消费面验收 IT 的状态边界夹具，NursingVitalSignFlowIT 批准口径）。
     * exec_item_name 落 'drug'——生成域快照契约（itemName=转抄类型子键，药品医嘱子键=drug，
     * casSignReceiveBatchByOrder 药品类谓词锚）。
     *
     * @param id          行主键，非空
     * @param executionNo 执行单号，非空
     * @param m04OrderNo  医嘱号，非空
     * @param m04PlanNo   计划号（NULL=临时快照形态），可空
     */
    private void seedExecution(long id, String executionNo, String m04OrderNo, String m04PlanNo) {
        jdbcTemplate.update(
                "INSERT INTO nursing.order_execution"
                        + " (id, execution_no, m04_order_no, m04_plan_no, visit_id, patient_id, ward_id,"
                        + " execution_type, exec_item_code, exec_item_name, plan_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'GENERIC', ?, 'drug', now())",
                id,
                executionNo,
                m04OrderNo,
                m04PlanNo,
                VISIT_ID,
                PATIENT_ID,
                WARD_ID,
                m04OrderNo);
    }

    /**
     * 注入 pharmacy.dispense.completed 上游帧（门诊行=V702 旧载荷原子集[rxNo/prescriptionId
     * 承载、无 V1111 住院字段]；住院行=V1111 四字段扩列全量载荷[rxNo/prescriptionId 语义位 null]）。
     *
     * @param traceId       全链路追踪号（重复投递以 -replay 后缀区分）
     * @param m04OrderNo    住院医嘱号；null=门诊旧载荷行
     * @param dispenseType  摆药类型（INPATIENT_PIVA/INPATIENT_DOSE）；门诊行可为 null
     * @param planNo        摆药计划号；门诊行可为 null
     * @param traceCode     首行溯源码（袋签码推导输入）；门诊行可为 null
     */
    private void publishDispenseCompleted(
            String traceId, String m04OrderNo, String dispenseType, String planNo, String traceCode) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dispenseNo", "D-IT-SIGN-" + System.nanoTime() % 1_000_000L)
                .put("patientId", PATIENT_ID)
                .put("visitId", VISIT_ID);
        if (m04OrderNo != null) {
            payload.put("m04OrderNo", m04OrderNo)
                    .put("dispenseType", dispenseType)
                    .put("dispensePlanNo", planNo);
            ObjectNode line = payload.putArray("lines").addObject();
            line.put("itemCode", "IT-SIGN-DRUG").put("batchNo", "B-IT-SIGN");
            line.putArray("traceCodes").add(traceCode);
        } else {
            // 门诊旧载荷行（V702 原子集）：处方锚在位——billing/outpatient 既有消费方按门诊语义
            // 正常收敛（幂等 0 行回写），不触发其载荷守卫死信
            payload.put("prescriptionId", 920_701L).put("rxNo", "RX-IT-SIGN-0001");
        }
        rabbitTemplate.convertAndSend(
                "fy.topic",
                PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED,
                envelopeCodec.create(
                        Clock.systemUTC(),
                        "pharmacy",
                        PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED,
                        traceId,
                        objectMapper.convertValue(payload, Map.class)));
    }

    /** 该医嘱执行单行数（幂等断言锚——重复投递不得新增行）。 */
    private int executionCount(String m04OrderNo) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND deleted = 0",
                Integer.class,
                m04OrderNo);
        return n == null ? 0 : n;
    }

    /** 该医嘱指定状态行数（签收/升格断言锚）。 */
    private int executionCount(String m04OrderNo, String status) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.order_execution WHERE m04_order_no = ? AND status = ? AND deleted = 0",
                Integer.class,
                m04OrderNo,
                status);
        return n == null ? 0 : n;
    }

    /** nursing 消费该事件族的 PROCESSED 台账行数（非盲等实证锚）。 */
    private long nursingProcessedCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integration.received_event"
                        + " WHERE event_type = ? AND consumer_module = 'nursing' AND status = 'PROCESSED'",
                Long.class,
                PharmacyMessagingConstants.EVENT_DISPENSE_COMPLETED);
        return n == null ? 0 : n;
    }

    /** 监测挂接行数（PIVAS 升格建链断言锚）。 */
    private int monitorLinkCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM nursing.infusion_monitor_link WHERE deleted = 0", Integer.class);
        return n == null ? 0 : n;
    }

    /** 轮询等待业务条件成立（消费链异步收敛，超时附死信诊断）。 */
    private void awaitUntil(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + CONSUME_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!condition.getAsBoolean()) {
            // 超时诊断：死信台账定位消费断点（消费失败帧留痕面）
            var deadLetters = jdbcTemplate.queryForList("SELECT event_type, fail_reason FROM integration.dead_letter"
                    + " WHERE event_type LIKE 'pharmacy%' ORDER BY created_at DESC LIMIT 5");
            throw new IllegalStateException(description + "——等待超时，死信近帧=" + deadLetters);
        }
    }

    @Test
    @Order(1)
    @DisplayName("夹具与门诊旧载荷行：dispense.completed 无 m04OrderNo → 消费确认零副作用（哨兵行停留 CREATED）")
    void outpatientLegacyRowConsumedWithZeroSideEffect() {
        seedExecution(SENTINEL_ROW_ID, SENTINEL_EXECUTION, OUTPATIENT_SENTINEL_ORDER, null);
        long processedBefore = nursingProcessedCount();
        publishDispenseCompleted("it-sign-outpatient", null, null, null, null);
        awaitUntil("门诊旧载荷行消费确认（received_event PROCESSED）", () -> nursingProcessedCount() >= processedBefore + 1);
        // 零副作用实证（PROCESSED 登记后置即业务体已收敛）：哨兵行停留 CREATED、零升格、零建链
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        SENTINEL_EXECUTION))
                .as("门诊行零副作用：哨兵行停留 CREATED")
                .isEqualTo("CREATED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT execution_type FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        SENTINEL_EXECUTION))
                .as("门诊行不得触发 PIVAS 升格")
                .isEqualTo("GENERIC");
        assertThat(monitorLinkCount()).as("门诊行不得建监测挂接").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("住院行：dispense.completed（INPATIENT_PIVA）→ 执行单批量 SIGNED + 升格 INFUSION + 挂接建链")
    void inpatientRowDrivesSignoffAndInfusionUpgrade() {
        seedExecution(SNAPSHOT_ROW_ID, SNAPSHOT_EXECUTION, INPATIENT_ORDER, null);
        seedExecution(PLAN_ROW_ID, PLAN_EXECUTION, INPATIENT_ORDER, INPATIENT_PLAN);
        publishDispenseCompleted(
                "it-sign-inpatient", INPATIENT_ORDER, "INPATIENT_PIVA", "DP-IT-SIGN-0001", "TR-IT-SIGN-001");
        awaitUntil("住院行执行单批量 SIGNED（快照行+计划行）", () -> executionCount(INPATIENT_ORDER, "SIGNED") == 2);
        awaitUntil("PIVAS 升格 INFUSION（两行）", () -> "INFUSION"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT execution_type FROM nursing.order_execution WHERE execution_no = ?",
                        String.class,
                        PLAN_EXECUTION)));
        // 建链断言：计划行挂接落库（袋签码=首行溯源码推导）
        awaitUntil("监测挂接建链（bagLabelCode=首行溯源码）", () -> monitorLinkCount() >= 1);
        Map<String, Object> link = jdbcTemplate.queryForMap(
                "SELECT execution_no, bag_label_code, link_status FROM nursing.infusion_monitor_link"
                        + " WHERE execution_no = ? AND deleted = 0",
                PLAN_EXECUTION);
        assertThat(link.get("bag_label_code")).as("袋签码=lines 首个溯源码推导").isEqualTo("TR-IT-SIGN-001");
        assertThat(link.get("link_status")).as("建链初始态=监测中").isEqualTo("MONITORING");
    }

    @Test
    @Order(3)
    @DisplayName("重复投递幂等：同载荷新 eventId 重投，SIGNED 行数/挂接行数零变化")
    void redeliveredInpatientRowIsIdempotent() {
        long processedBefore = nursingProcessedCount();
        int signedBefore = executionCount(INPATIENT_ORDER, "SIGNED");
        int linksBefore = monitorLinkCount();
        int rowsBefore = executionCount(INPATIENT_ORDER);
        publishDispenseCompleted(
                "it-sign-inpatient-replay", INPATIENT_ORDER, "INPATIENT_PIVA", "DP-IT-SIGN-0001", "TR-IT-SIGN-001");
        awaitUntil("重投帧消费确认（received_event PROCESSED）", () -> nursingProcessedCount() >= processedBefore + 1);
        // 覆盖迟到帧残余窗口（PharmacyDispenseGuardIT Order(3) 同型有界兜底）
        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(executionCount(INPATIENT_ORDER, "SIGNED"))
                .as("重复投递签收行数不变（CREATED 谓词幂等）")
                .isEqualTo(signedBefore);
        assertThat(executionCount(INPATIENT_ORDER)).as("重复投递不得新增执行单行").isEqualTo(rowsBefore);
        assertThat(monitorLinkCount()).as("重复投递挂接行数不变（唯一索引吞并）").isEqualTo(linksBefore);
    }
}

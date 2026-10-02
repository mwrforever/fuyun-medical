package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;

/**
 * 住院就诊事件族监听器单测（Task 7 投影写入四路实装后的全量冻结集）：visit.admitted
 * upsert（新行落库 / uk 冲突回查合并 / 幂等重放零新建）、visit.transferred 归属更新
 * （ward 切换 bed_no 留旧值——W-34 裁决：transferred 载荷无床号，禁落床位 id 文本）、
 * visit.discharged 逻辑删（deleted=1，同 visitId 再入院可重新 upsert）、bed.changed
 * 补床号（patientId 命中在册投影行；占床帧先于入科帧到达的乱序自愈重试）+ 执行域
 * 既有两路（转科重定向 / 出院终清）+ 不合规帧死信守卫 + 五队列精确绑定锚。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托
 * （MedicationOrderReviewListenerTest 同款单测形态）；条件更新 SQL 契约直读 @Update
 * 注解断言（GC26 可执行锚）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientVisitEventListenerTest {

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026100200001";

    /** 转出病区编码（重定向 fromWardId 谓词） */
    private static final String FROM_WARD = "W01";

    /** 转入病区编码（重定向目标） */
    private static final String TO_WARD = "W02";

    /** 转入床位 id（V800 id 49 冻结载荷仅携 id 无床号——投影 bed_no 留旧值由 bed.changed 补齐） */
    private static final long TO_BED_ID = 501L;

    /** 消费链路无登录上下文的操作者（与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private OrderExecutionMapper orderExecutionMapper;

    @Mock
    private NursingTaskMapper nursingTaskMapper;

    @Mock
    private NursingWardPatientMapper wardPatientMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("① 入科路：投影 upsert 新行落库（bed_no 空占位待 bed.changed 补齐、展示名经详情嵌查不落行），执行域零触达")
    void handleVisitAdmittedInsertsProjectionRow() throws Exception {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);
        when(wardPatientMapper.insert(any(NursingWardPatient.class))).thenAnswer(inv -> {
            inv.getArgument(0, NursingWardPatient.class).setId(1L);
            return 1;
        });

        listener().handleVisitAdmitted(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","wardId":"W01",
                         "bedId":"501","admittedAt":"2026-10-02T00:00:00Z","nursingLevel":"CRITICAL"}
                        """)));

        // 投影行六要素钉死：定位键（visit/patient/ward）+ 护理级别 + 入科时点；bed_no 载荷无床号
        // 落空占位（W-34 裁决：该列语义=床号文本，禁落床位 id 文本）；展示名列 NOT NULL 落空串占位
        ArgumentCaptor<NursingWardPatient> inserted = ArgumentCaptor.forClass(NursingWardPatient.class);
        verify(wardPatientMapper).insert(inserted.capture());
        NursingWardPatient row = inserted.getValue();
        assertThat(row.getVisitId()).isEqualTo(VISIT);
        assertThat(row.getPatientId()).isEqualTo(700101L);
        assertThat(row.getWardId()).isEqualTo("W01");
        assertThat(row.getNursingLevel()).isEqualTo("CRITICAL");
        assertThat(row.getAdmittedAt())
                .isEqualTo(OffsetDateTime.ofInstant(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
        assertThat(row.getBedNo()).isEmpty();
        assertThat(row.getPatientName()).isEmpty();
        assertThat(row.getCreatedBy()).isEqualTo(SYSTEM_OPERATOR);
        // 入科无执行域动作：撤销/重定向 CAS 零触达
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    @Test
    @DisplayName("①′ 入科路：载荷 nursingLevel 缺省 NORMAL（V801 列默认同源），床号未知零拼接")
    void handleVisitAdmittedDefaultsNursingLevel() throws Exception {
        when(wardPatientMapper.selectOne(any())).thenReturn(null);
        when(wardPatientMapper.insert(any(NursingWardPatient.class))).thenReturn(1);

        listener().handleVisitAdmitted(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","wardId":"W01",
                         "bedId":"501","admittedAt":"2026-10-02T00:00:00Z"}
                        """)));

        ArgumentCaptor<NursingWardPatient> inserted = ArgumentCaptor.forClass(NursingWardPatient.class);
        verify(wardPatientMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getNursingLevel()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("② 入科路 uk 冲突回查合并（D-23 同款两层兜底）：并发双投递 insert 撞 uk_ward_patient_visit → 回查命中转条件更新")
    void handleVisitAdmittedMergesOnUniqueConflict() throws Exception {
        NursingWardPatient concurrent = projectionRow();
        // 首查未命中（并发窗口）→ insert 撞唯一约束（对方先落行）→ 回查命中 → 转入科属性条件更新
        when(wardPatientMapper.selectOne(any())).thenReturn(null, concurrent);
        when(wardPatientMapper.insert(any(NursingWardPatient.class)))
                .thenThrow(new DuplicateKeyException("uk_ward_patient_visit"));
        when(wardPatientMapper.casAdmitRefresh(
                        VISIT,
                        "W01",
                        "NORMAL",
                        OffsetDateTime.ofInstant(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
                        SYSTEM_OPERATOR))
                .thenReturn(1);

        listener().handleVisitAdmitted(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","wardId":"W01",
                         "bedId":"501","admittedAt":"2026-10-02T00:00:00Z","nursingLevel":"NORMAL"}
                        """)));

        // 冲突收敛：insert 恰一次（不再重试盲插），冲突行按 visit_id 回查后走条件更新
        verify(wardPatientMapper).insert(any(NursingWardPatient.class));
        verify(wardPatientMapper)
                .casAdmitRefresh(
                        VISIT,
                        "W01",
                        "NORMAL",
                        OffsetDateTime.ofInstant(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
                        SYSTEM_OPERATOR);
    }

    @Test
    @DisplayName("③ 入科路幂等重放：在册行已存在 → 仅刷新入科属性条件更新，零新建（重放零副作用）")
    void handleVisitAdmittedReplaysOntoExistingRowWithoutInsert() throws Exception {
        when(wardPatientMapper.selectOne(any())).thenReturn(projectionRow());
        when(wardPatientMapper.casAdmitRefresh(any(), any(), any(), any(), any()))
                .thenReturn(1);

        listener().handleVisitAdmitted(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","wardId":"W01",
                         "bedId":"501","admittedAt":"2026-10-02T00:00:00Z","nursingLevel":"NORMAL"}
                        """)));

        // 重放幂等：不新建行，仅入科属性（ward/护理级别/入科时点）条件更新（visit_id + deleted=0 谓词）
        verify(wardPatientMapper, never()).insert(any(NursingWardPatient.class));
        verify(wardPatientMapper)
                .casAdmitRefresh(
                        VISIT,
                        "W01",
                        "NORMAL",
                        OffsetDateTime.ofInstant(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
                        SYSTEM_OPERATOR);
        String sql = projectionSql(
                "casAdmitRefresh", String.class, String.class, String.class, OffsetDateTime.class, String.class);
        assertThat(sql).contains("SET ward_id").contains("nursing_level").contains("admitted_at");
        assertThat(sql).contains("WHERE visit_id = #{visitId}").contains("deleted = 0");
        // 投影入科刷新禁触 bed_no（床号唯一写入面=bed.changed）与展示名（详情嵌查承载）
        assertThat(sql).doesNotContain("bed_no").doesNotContain("patient_name");
    }

    @Test
    @DisplayName("④ 转科路：投影归属更新（ward 切换、bed_no 留旧值——W-34 裁决禁落床位 id 文本）+ 未执行执行单重定向")
    void handleVisitTransferredRedirectsUnexecutedExecutions() throws Exception {
        when(orderExecutionMapper.casRedirectWard(VISIT, FROM_WARD, TO_WARD, "501", "system"))
                .thenReturn(2);
        when(wardPatientMapper.casTransferWard(VISIT, FROM_WARD, TO_WARD, "system"))
                .thenReturn(1);

        listener().handleVisitTransferred(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","fromWardId":"W01",
                         "fromBedId":"500","toWardId":"W02","toBedId":"501",
                         "transferredAt":"2026-10-02T03:00:00Z"}
                        """)));

        // 转科三分规则 M04 侧⑤：未执行临时计划随患者转移新病区（执行单 bed_no 落床位 id 文本承载）
        verify(orderExecutionMapper).casRedirectWard(VISIT, FROM_WARD, TO_WARD, String.valueOf(TO_BED_ID), "system");
        // 投影归属：ward 切换由本事件承载；bed_no 留旧值待 bed.changed 补齐（fromWard 谓词幂等——重放 0 行）
        verify(wardPatientMapper).casTransferWard(VISIT, FROM_WARD, TO_WARD, "system");
        String sql = projectionSql("casTransferWard", String.class, String.class, String.class, String.class);
        assertThat(sql).contains("SET ward_id = #{toWardId}");
        assertThat(sql)
                .contains("WHERE visit_id = #{visitId} AND ward_id = #{fromWardId}")
                .contains("deleted = 0");
        assertThat(sql).doesNotContain("bed_no");
        verifyNoInteractions(nursingTaskMapper);
    }

    @Test
    @DisplayName("⑤ 出院申请路：清退提示占位不改状态（三 mapper 零触达——WS board 推送归后续任务）")
    void handleDischargeRequestedTouchesNoState() throws Exception {
        assertThatCode(() -> listener().handleDischargeRequested(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101",
                         "requestedAt":"2026-10-02T04:00:00Z"}
                        """))))
                .doesNotThrowAnyException();
        // 不改状态红线：在途任务与执行单保持原态，仅清退提示
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper, wardPatientMapper);
    }

    @Test
    @DisplayName("⑥ 出院终清路：未执行执行单与在途任务批量 CANCELLED + 投影逻辑删（deleted=1，再入院可重新 upsert）")
    void handleVisitDischargedCancelsInFlightRows() throws Exception {
        when(orderExecutionMapper.casCancelByVisit(VISIT, "出院终清", "system")).thenReturn(4);
        when(nursingTaskMapper.casCancelByVisit(VISIT, "出院终清", "system")).thenReturn(1);
        when(wardPatientMapper.casDischarge(VISIT, "system")).thenReturn(1);

        listener().handleVisitDischarged(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101",
                         "dischargedAt":"2026-10-02T05:00:00Z"}
                        """)));

        // 终清三面：未执行执行单（EXECUTING 不动）+ 在途任务（PENDING/IN_PROGRESS）+ 投影逻辑删
        verify(orderExecutionMapper).casCancelByVisit(VISIT, "出院终清", "system");
        verify(nursingTaskMapper).casCancelByVisit(VISIT, "出院终清", "system");
        verify(wardPatientMapper).casDischarge(VISIT, "system");
        // 逻辑删契约钉死：deleted=1 单语句 + deleted=0 在册谓词（uk_ward_patient_visit 带 deleted=0
        // 谓词，逻辑删后同 visitId 再入院可重新 upsert——W-34/V1108 口径）
        String sql = projectionSql("casDischarge", String.class, String.class);
        assertThat(sql)
                .contains("SET deleted = 1")
                .contains("WHERE visit_id = #{visitId}")
                .contains("deleted = 0");
    }

    @Test
    @DisplayName("⑦ 床位变更路：有患者主体按 patientId 命中投影行更新床号（IS DISTINCT FROM 谓词重放 0 行）")
    void handleBedChangedUpdatesProjectionBedNo() throws Exception {
        when(wardPatientMapper.casUpdateBedNoByPatient(700101L, "12", "system")).thenReturn(1);

        listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"OCCUPIED","patientId":"700101"}
                """)));

        // 床号唯一写入面：按占用患者主索引定位在册投影行（不按 ward 过滤——bed.changed 先于
        // transferred 到达时旧病区行先补新床号，transferred 后到即收敛，双向乱序终态一致）
        verify(wardPatientMapper).casUpdateBedNoByPatient(700101L, "12", "system");
        String sql = projectionSql("casUpdateBedNoByPatient", long.class, String.class, String.class);
        assertThat(sql).contains("SET bed_no = #{bedNo}");
        assertThat(sql).contains("WHERE patient_id = #{patientId}").contains("deleted = 0");
        assertThat(sql).contains("bed_no IS DISTINCT FROM #{bedNo}");
    }

    @Test
    @DisplayName("⑦′ 床位变更路乱序自愈：占床帧先于入科帧到达（首更新 0 行）→ 短暂等待后重试一次命中")
    void handleBedChangedRetriesOnceWhenProjectionRowNotYetCreated() throws Exception {
        when(wardPatientMapper.casUpdateBedNoByPatient(700101L, "12", "system")).thenReturn(0, 1);

        listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"OCCUPIED","patientId":"700101"}
                """)));

        // 乱序窗口自愈：admitted 与 bed.changed(OCCUPIED) 同事务异队列发布、消费无顺序保证——
        // 首更新 0 行后等待入科 upsert 落行再重试一次（恰好两次触达，不无限重试）
        verify(wardPatientMapper, org.mockito.Mockito.times(2)).casUpdateBedNoByPatient(700101L, "12", "system");
    }

    @Test
    @DisplayName("⑦″ 床位变更路终未命中：等待重试仍 0 行 → warn 留痕直返（宁缺勿错，不抛不无限重试）")
    void handleBedChangedToleratesPersistentMiss() throws Exception {
        when(wardPatientMapper.casUpdateBedNoByPatient(700101L, "12", "system")).thenReturn(0, 0);

        assertThatCode(() -> listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"OCCUPIED","patientId":"700101"}
                """))))
                .doesNotThrowAnyException();

        // 数据异常防御边界：patientId 无在册投影行（如手工清库）不抛出——warn 留痕交人工核查
        verify(wardPatientMapper, org.mockito.Mockito.times(2)).casUpdateBedNoByPatient(700101L, "12", "system");
    }

    @Test
    @DisplayName("⑧ 床位变更路：无患者主体（预占/释放/消毒/维修）直返，投影与执行域全零触达")
    void handleBedChangedSkipsWhenNoPatientSubject() throws Exception {
        assertThatCode(() -> listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"CLEANING","patientId":null}
                """))))
                .doesNotThrowAnyException();
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper, wardPatientMapper);
    }

    @Test
    @DisplayName("⑨ 字段缺失防御：转科缺 toWardId、终清缺 visitId 均死信拒收且零重定向")
    void handleRoutesRejectMalformedFrames() throws Exception {
        // 转科路缺 toWardId：不合规帧显式抛出进死信留痕
        ObjectNode missingWard = dischargeTransferPayload();
        missingWard.remove("toWardId");
        assertThatThrownBy(() -> listener().handleVisitTransferred(envelope(missingWard)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toWardId");

        // 终清路缺 visitId
        ObjectNode missingVisit = payload("{\"patientId\":\"700101\",\"dischargedAt\":\"2026-10-02T05:00:00Z\"}");
        assertThatThrownBy(() -> listener().handleVisitDischarged(envelope(missingVisit)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visitId");

        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper, wardPatientMapper);
    }

    @Test
    @DisplayName("⑩ 五路队列精确绑定锚：@RabbitListener 队列名与治理推导名逐字一致（q.nursing.<登记名>）")
    void listenerBindsExactQueues() throws Exception {
        assertQueue("onVisitAdmitted", "q.nursing.inpatient.visit.admitted");
        assertQueue("onVisitTransferred", "q.nursing.inpatient.visit.transferred");
        assertQueue("onDischargeRequested", "q.nursing.inpatient.visit.discharge-requested");
        assertQueue("onVisitDischarged", "q.nursing.inpatient.visit.discharged");
        assertQueue("onBedChanged", "q.nursing.inpatient.bed.changed");
    }

    @Test
    @DisplayName("⑪ 入口仅做 consume 委托（三段式标准形态，五路业务体不重复触达）")
    void entriesDelegateToConsumerSupport() {
        InpatientVisitEventListener listener = listener();
        Message message = new Message(new byte[0], new MessageProperties());

        listener.onVisitAdmitted(message);
        listener.onVisitTransferred(message);
        listener.onDischargeRequested(message);
        listener.onVisitDischarged(message);
        listener.onBedChanged(message);

        verify(consumerSupport, org.mockito.Mockito.times(5)).consume(any(Message.class), any());
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper, wardPatientMapper);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 在册投影行替身（W01/NORMAL，幂等重放与冲突回查用例载体）。 */
    private NursingWardPatient projectionRow() {
        NursingWardPatient row = new NursingWardPatient();
        row.setId(5L);
        row.setVisitId(VISIT);
        row.setPatientId(700101L);
        row.setWardId(FROM_WARD);
        row.setBedNo("08");
        row.setPatientName("");
        row.setNursingLevel("NORMAL");
        row.setAdmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return row;
    }

    /** 受测监听器（构造器注入四 mock——投影 mapper 为 Task 7 实装新增写面）。 */
    private InpatientVisitEventListener listener() {
        return new InpatientVisitEventListener(
                consumerSupport, orderExecutionMapper, nursingTaskMapper, wardPatientMapper);
    }

    /** 信封手工构造（ eventType=登记名，producer=inpatient；五路共用定位键 visitId）。 */
    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(
                "nurs-ev-visit",
                Instant.now(),
                "inpatient",
                NursingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_TRANSFERRED,
                "1",
                null,
                payload);
    }

    /** 载荷 JSON 构造。 */
    private ObjectNode payload(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    /** 转科路基线载荷（可变副本供守卫用例裁剪字段）。 */
    private ObjectNode dischargeTransferPayload() throws Exception {
        return payload("{\"visitId\":\"I2026100200001\",\"patientId\":\"700101\",\"fromWardId\":\"W01\","
                + "\"fromBedId\":\"500\",\"toWardId\":\"W02\",\"toBedId\":\"501\","
                + "\"transferredAt\":\"2026-10-02T03:00:00Z\"}");
    }

    /** 断言入口方法 @RabbitListener 队列名与治理推导名（q.nursing.<登记名>）逐字一致。 */
    private static void assertQueue(String method, String expectedQueue) throws Exception {
        RabbitListener annotation = InpatientVisitEventListener.class
                .getMethod(method, Message.class)
                .getAnnotation(RabbitListener.class);
        assertThat(annotation).as("入口方法 %s 须挂 @RabbitListener", method).isNotNull();
        assertThat(annotation.queues()).containsExactly(expectedQueue);
    }

    /**
     * 直读投影 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String projectionSql(String method, Class<?>... paramTypes) {
        try {
            Update update =
                    NursingWardPatientMapper.class.getMethod(method, paramTypes).getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            return fail("mapper 方法不存在：" + method, e);
        }
    }
}

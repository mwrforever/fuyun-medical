package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 住院就诊事件族监听器单测（Task 4，visit.admitted/transferred/discharge-requested/discharged/
 * bed.changed 五路）：转科执行单重定向与出院终清两执行域真路径逐参断言 + 投影写入四路占位零
 * 误伤 + 不合规帧死信守卫 + 五队列精确绑定锚。业务体为包级 handle 方法，
 * @RabbitListener 入口仅做 consume 委托（MedicationOrderReviewListenerTest 同款单测形态）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientVisitEventListenerTest {

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026100200001";

    /** 转出病区编码（重定向 fromWardId 谓词） */
    private static final String FROM_WARD = "W01";

    /** 转入病区编码（重定向目标） */
    private static final String TO_WARD = "W02";

    /** 转入床位 id（V800 id 49 冻结载荷仅携 id 无床号——bed_no 落 id 文本形态承载） */
    private static final long TO_BED_ID = 501L;

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private OrderExecutionMapper orderExecutionMapper;

    @Mock
    private NursingTaskMapper nursingTaskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("① 入科路：投影 upsert 占位（Task 7 实装），执行域零触达")
    void handleVisitAdmittedHitsProjectionPlaceholderOnly() throws Exception {
        assertThatCode(() -> listener().handleVisitAdmitted(envelope(payload("""
                                {"visitId":"I2026100200001","patientId":"700101","wardId":"W01",
                                 "bedId":"501","admittedAt":"2026-10-02T00:00:00Z","nursingLevel":"NORMAL"}
                                """))))
                .doesNotThrowAnyException();
        // 入科无执行域动作：撤销/重定向 CAS 零触达（投影写入归 Task 7）
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    @Test
    @DisplayName("② 转科路：未执行执行单重定向（ward 切换+bed 随事件，fromWard 谓词幂等，计划时间不动）")
    void handleVisitTransferredRedirectsUnexecutedExecutions() throws Exception {
        when(orderExecutionMapper.casRedirectWard(VISIT, FROM_WARD, TO_WARD, "501", "system"))
                .thenReturn(2);

        listener().handleVisitTransferred(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101","fromWardId":"W01",
                         "fromBedId":"500","toWardId":"W02","toBedId":"501",
                         "transferredAt":"2026-10-02T03:00:00Z"}
                        """)));

        // 转科三分规则 M04 侧⑤：未执行临时计划随患者转移新病区（bed_no 落床位 id 文本承载）
        verify(orderExecutionMapper).casRedirectWard(VISIT, FROM_WARD, TO_WARD, String.valueOf(TO_BED_ID), "system");
        verifyNoInteractions(nursingTaskMapper);
    }

    @Test
    @DisplayName("③ 出院申请路：清退提示占位不改状态（两 mapper 零触达——WS board 推送归后续任务）")
    void handleDischargeRequestedTouchesNoState() throws Exception {
        assertThatCode(() -> listener().handleDischargeRequested(envelope(payload("""
                                {"visitId":"I2026100200001","patientId":"700101",
                                 "requestedAt":"2026-10-02T04:00:00Z"}
                                """))))
                .doesNotThrowAnyException();
        // 不改状态红线：在途任务与执行单保持原态，仅清退提示
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    @Test
    @DisplayName("④ 出院终清路：未执行执行单与在途任务批量 CANCELLED（原因固定「出院终清」）")
    void handleVisitDischargedCancelsInFlightRows() throws Exception {
        when(orderExecutionMapper.casCancelByVisit(VISIT, "出院终清", "system")).thenReturn(4);
        when(nursingTaskMapper.casCancelByVisit(VISIT, "出院终清", "system")).thenReturn(1);

        listener().handleVisitDischarged(envelope(payload("""
                        {"visitId":"I2026100200001","patientId":"700101",
                         "dischargedAt":"2026-10-02T05:00:00Z"}
                        """)));

        // 终清双面：未执行执行单（EXECUTING 不动）+ 在途任务（PENDING/IN_PROGRESS）
        verify(orderExecutionMapper).casCancelByVisit(VISIT, "出院终清", "system");
        verify(nursingTaskMapper).casCancelByVisit(VISIT, "出院终清", "system");
    }

    @Test
    @DisplayName("⑤ 床位变更路：有患者主体走投影占位零触达；无主体（预占/释放/消毒/维修）直返")
    void handleBedChangedSkipsWhenNoPatientSubject() throws Exception {
        // 有主体（占床/转入）：投影 bed_no 更新占位（Task 7 实装），执行域零触达
        assertThatCode(() -> listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"OCCUPIED","patientId":"700101"}
                """))))
                .doesNotThrowAnyException();
        // 无主体：投影与执行域全零触达
        assertThatCode(() -> listener().handleBedChanged(envelope(payload("""
                {"wardId":"W02","bedId":"501","bedNo":"12","bedStatus":"CLEANING","patientId":null}
                """))))
                .doesNotThrowAnyException();
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    @Test
    @DisplayName("⑥ 字段缺失防御：转科缺 toWardId、终清缺 visitId 均死信拒收且零重定向")
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

        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    @Test
    @DisplayName("⑦ 五路队列精确绑定锚：@RabbitListener 队列名与治理推导名逐字一致（q.nursing.<登记名>）")
    void listenerBindsExactQueues() throws Exception {
        assertQueue("onVisitAdmitted", "q.nursing.inpatient.visit.admitted");
        assertQueue("onVisitTransferred", "q.nursing.inpatient.visit.transferred");
        assertQueue("onDischargeRequested", "q.nursing.inpatient.visit.discharge-requested");
        assertQueue("onVisitDischarged", "q.nursing.inpatient.visit.discharged");
        assertQueue("onBedChanged", "q.nursing.inpatient.bed.changed");
    }

    @Test
    @DisplayName("⑧ 入口仅做 consume 委托（三段式标准形态，五路业务体不重复触达）")
    void entriesDelegateToConsumerSupport() {
        InpatientVisitEventListener listener = listener();
        Message message = new Message(new byte[0], new MessageProperties());

        listener.onVisitAdmitted(message);
        listener.onVisitTransferred(message);
        listener.onDischargeRequested(message);
        listener.onVisitDischarged(message);
        listener.onBedChanged(message);

        verify(consumerSupport, org.mockito.Mockito.times(5)).consume(any(Message.class), any());
        verifyNoInteractions(orderExecutionMapper, nursingTaskMapper);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 受测监听器（构造器注入三 mock）。 */
    private InpatientVisitEventListener listener() {
        return new InpatientVisitEventListener(consumerSupport, orderExecutionMapper, nursingTaskMapper);
    }

    /** 信封手工构造（ eventType=登记名，producer=inpatient；五路共用定位键 visitId）。 */
    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(
                "nurs-ev-visit",
                Instant.now(Clock.systemUTC()),
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
}

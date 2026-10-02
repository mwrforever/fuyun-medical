package com.fuyun.pharmacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.constants.PharmacyMessagingConstants;
import com.fuyun.pharmacy.service.IDispensePlanService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 住院终清事件监听器单测（P2 PR-3 Task 8）：order.stopped/visit.discharged 两路载荷冻结子集
 * 解析透传 + 不合规帧死信守卫 + GC15 SYSTEM 桥（消费执行期操作者落位/finally 清理）+
 * 两队列精确绑定锚（MedicationOrderReviewListenerTest 同款单测形态）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientTerminalEventListenerTest {

    /** M04 医嘱号（载荷定位键） */
    private static final String ORDER_NO = "M20261002001";

    /** I 型 14 位合法住院就诊号 */
    private static final String VISIT = "I2026100200001";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IDispensePlanService dispensePlanService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("① 停嘱路：载荷 m04OrderNo/stopReason 解析透传作废域（V800 id 44 冻结子集）")
    void handleOrderStoppedParsesFrozenPayloadToService() throws Exception {
        listener().handleOrderStopped(envelope(PharmacyMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED, """
                {"m04OrderNo":"M20261002001","stopReason":"医嘱停止·不良反应"}
                """));

        verify(dispensePlanService).cancelByOrderTerminal(ORDER_NO, "医嘱停止·不良反应");
    }

    @Test
    @DisplayName("② 出院路：载荷 visitId/patientId/dischargedAt 解析透传终清域（V800 id 51 冻结子集）")
    void handleVisitDischargedParsesFrozenPayloadToService() throws Exception {
        listener()
                .handleVisitDischarged(envelope(PharmacyMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGED, """
                {"visitId":"I2026100200001","patientId":700101,"dischargedAt":"2026-10-02T06:30:00Z"}
                """));

        verify(dispensePlanService).cancelByVisitDischarge(VISIT);
    }

    @Test
    @DisplayName("③ 不合规帧死信守卫：缺 m04OrderNo/stopReason、缺 patientId——ISE 抛出禁静默吞")
    void malformedPayloadsAreRejectedToDeadLetter() throws Exception {
        InpatientTerminalEventListener unit = listener();
        // 停嘱缺 stopReason
        assertThatThrownBy(() -> unit.handleOrderStopped(envelope(
                        PharmacyMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED,
                        "{\"m04OrderNo\":\"M20261002001\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopReason");
        // 出院缺 patientId（非法数值 0）
        assertThatThrownBy(() -> unit.handleVisitDischarged(envelope(
                        PharmacyMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGED,
                        "{\"visitId\":\"I2026100200001\",\"patientId\":0,\"dischargedAt\":\"2026-10-02T06:30:00Z\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patientId");
        // 守卫拒帧零业务触达（死信留痕归构件，服务面不被污染）
        verifyNoInteractions(dispensePlanService);
    }

    @Test
    @DisplayName("④ GC15 SYSTEM 桥：消费执行期操作者=SYSTEM、结束后线程清理（finally 防残留）")
    void mqEntryBridgesSystemOperatorWithFinallyCleanup() throws Exception {
        InpatientTerminalEventListener unit = listener();
        AtomicReference<String> duringConsume = new AtomicReference<>("未触达");
        // consume 执行期回读操作者（桥接落位证据）——doAnswer 形态承载执行期采样
        doAnswer(invocation -> {
                    duringConsume.set(OperatorContextHolder.get());
                    return null;
                })
                .when(consumerSupport)
                .consume(any(Message.class), any());
        Message message = new Message("{}".getBytes(), new MessageProperties());

        unit.onOrderStopped(message);
        assertThat(duringConsume.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();

        unit.onVisitDischarged(message);
        assertThat(duringConsume.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("⑤ 队列绑定锚：两 @RabbitListener 精确绑定 q.pharmacy.<登记名>（V800 id 44/51 治理推导名）")
    void rabbitListenersBindExactGovernanceQueues() throws Exception {
        Method stopped = InpatientTerminalEventListener.class.getMethod("onOrderStopped", Message.class);
        String stoppedQueue = stopped.getAnnotation(RabbitListener.class).queues()[0];
        assertThat(stoppedQueue).isEqualTo("q.pharmacy.inpatient.order.stopped");

        Method discharged = InpatientTerminalEventListener.class.getMethod("onVisitDischarged", Message.class);
        String dischargedQueue = discharged.getAnnotation(RabbitListener.class).queues()[0];
        assertThat(dischargedQueue).isEqualTo("q.pharmacy.inpatient.visit.discharged");
    }

    private InpatientTerminalEventListener listener() {
        return new InpatientTerminalEventListener(consumerSupport, dispensePlanService);
    }

    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return envelope(eventType, objectMapper.readTree(payloadJson));
    }

    private EventEnvelope envelope(String eventType, JsonNode payload) {
        return new EventEnvelope(
                "ph-ev-" + eventType, Instant.now(Clock.systemUTC()), "inpatient", eventType, "1", null, payload);
    }
}

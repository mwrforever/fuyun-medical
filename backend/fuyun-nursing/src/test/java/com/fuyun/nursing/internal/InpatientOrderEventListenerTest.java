package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.service.IOrderExecutionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 住院医嘱事件族监听器单测（Task 4，order.transferred/order-plan.generated/stopped/cancelled
 * 四路合一）：envelope JSON 冻结子集逐路解析透传 + 不合规帧死信守卫 + 四队列精确绑定锚。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托
 * （MedicationOrderReviewListenerTest 同款单测形态）。
 */
@ExtendWith(MockitoExtension.class)
class InpatientOrderEventListenerTest {

    /** M04 医嘱号（载荷定位键） */
    private static final String ORDER_NO = "M20261002001";

    /** I 型 14 位合法 visit_id */
    private static final String VISIT = "I2026100200001";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IOrderExecutionService orderExecutionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("① 转抄路：载荷五字段解析透传（类型快照+临时单生成入参逐字断言）")
    void handleOrderTransferredParsesFrozenPayloadToService() throws Exception {
        listener()
                .handleOrderTransferred(envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_TRANSFERRED, """
                {"m04OrderNo":"M20261002001","visitId":"I2026100200001","patientId":"700101",
                 "transferType":"drug","firstTransferredAt":"2026-10-02T01:30:00Z"}
                """));

        verify(orderExecutionService)
                .onOrderTransferred(ORDER_NO, VISIT, 700101L, "drug", Instant.parse("2026-10-02T01:30:00Z"));
    }

    @Test
    @DisplayName("② 计划生成路：planDate/planNos[]/planTimes[] 冻结子集解析透传（LocalDate 与两列表）")
    void handleOrderPlanGeneratedParsesArraysToService() throws Exception {
        listener()
                .handleOrderPlanGenerated(
                        envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED, """
                {"m04OrderNo":"M20261002001","visitId":"I2026100200001","patientId":"700101",
                 "planDate":"2026-10-03","planNos":["PL2026100300001","PL2026100300002"],
                 "planTimes":["08:00","09:00"]}
                """));

        verify(orderExecutionService)
                .onPlanGenerated(
                        ORDER_NO,
                        VISIT,
                        700101L,
                        LocalDate.of(2026, 10, 3),
                        List.of("PL2026100300001", "PL2026100300002"),
                        List.of("08:00", "09:00"));
    }

    @Test
    @DisplayName("③ 停嘱路：stopReason 透传撤销（kind=STOPPED，仅日志语义区分）")
    void handleOrderStoppedPassesReasonAsStopped() throws Exception {
        listener().handleOrderStopped(envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_STOPPED, """
                {"m04OrderNo":"M20261002001","visitId":"I2026100200001","patientId":"700101",
                 "stoppedAt":"2026-10-02T02:00:00Z","stopOperator":"2001","stopReason":"患者病情好转"}
                """));

        verify(orderExecutionService).onOrderTerminal(ORDER_NO, "患者病情好转", IOrderExecutionService.TerminalKind.STOPPED);
    }

    @Test
    @DisplayName("④ 作废路：cancelReason 透传撤销（kind=CANCELLED，与停嘱同款撤销面）")
    void handleOrderCancelledPassesReasonAsCancelled() throws Exception {
        listener().handleOrderCancelled(envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_CANCELLED, """
                {"m04OrderNo":"M20261002001","visitId":"I2026100200001","patientId":"700101",
                 "cancelledAt":"2026-10-02T02:30:00Z","cancelReason":"开立有误"}
                """));

        verify(orderExecutionService).onOrderTerminal(ORDER_NO, "开立有误", IOrderExecutionService.TerminalKind.CANCELLED);
    }

    @Test
    @DisplayName("⑤ 字段缺失防御：缺 transferType/visitId、planTimes 非数组、双数组长度不一致均死信拒收")
    void handleRoutesRejectMalformedFrames() throws Exception {
        InpatientOrderEventListener listener = listener();

        // 转抄路缺 transferType：不合规帧显式抛出进死信留痕
        ObjectNode missingType = transferPayload();
        missingType.remove("transferType");
        assertThatThrownBy(() -> listener.handleOrderTransferred(
                        envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_TRANSFERRED, missingType)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transferType");

        // 计划路缺 visitId
        ObjectNode missingVisit = planPayload();
        missingVisit.remove("visitId");
        assertThatThrownBy(() -> listener.handleOrderPlanGenerated(
                        envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED, missingVisit)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visitId");

        // 计划路 planTimes 非数组（冻结契约两集须为数组且下标对齐）
        ObjectNode badTimes = planPayload();
        badTimes.put("planTimes", "08:00");
        assertThatThrownBy(() -> listener.handleOrderPlanGenerated(
                        envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED, badTimes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planTimes");

        // 计划路双数组长度不一致（下标对齐契约违约——落单时点不可推测）
        ObjectNode mismatch = planPayload();
        mismatch.putArray("planTimes").add("08:00");
        assertThatThrownBy(() -> listener.handleOrderPlanGenerated(
                        envelope(NursingMessagingConstants.EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED, mismatch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("下标对齐");

        // 守卫命中前零业务触达
        verifyNoInteractions(orderExecutionService);
    }

    @Test
    @DisplayName("⑥ 四路队列精确绑定锚：@RabbitListener 队列名与治理推导名逐字一致（q.nursing.<登记名>）")
    void listenerBindsExactQueues() throws Exception {
        assertQueue("onOrderTransferred", "q.nursing.inpatient.order.transferred");
        assertQueue("onOrderPlanGenerated", "q.nursing.inpatient.order-plan.generated");
        assertQueue("onOrderStopped", "q.nursing.inpatient.order.stopped");
        assertQueue("onOrderCancelled", "q.nursing.inpatient.order.cancelled");
    }

    @Test
    @DisplayName("⑦ 入口仅做 consume 委托（三段式标准形态，四路业务体不重复触达）")
    void entriesDelegateToConsumerSupport() {
        InpatientOrderEventListener listener = listener();
        Message message = new Message(new byte[0], new MessageProperties());

        listener.onOrderTransferred(message);
        listener.onOrderPlanGenerated(message);
        listener.onOrderStopped(message);
        listener.onOrderCancelled(message);

        verify(consumerSupport, org.mockito.Mockito.times(4)).consume(any(Message.class), any());
        verifyNoInteractions(orderExecutionService);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** 受测监听器（构造器注入两 mock）。 */
    private InpatientOrderEventListener listener() {
        return new InpatientOrderEventListener(consumerSupport, orderExecutionService);
    }

    /** 信封手工构造（本域监听器单测同款形态：eventType=登记名，producer=inpatient）。 */
    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return envelope(eventType, objectMapper.readTree(payloadJson));
    }

    private EventEnvelope envelope(String eventType, JsonNode payload) {
        return new EventEnvelope(
                "nurs-ev-" + eventType, Instant.now(Clock.systemUTC()), "inpatient", eventType, "1", null, payload);
    }

    /** 转抄路基线载荷（可变副本供守卫用例裁剪字段）。 */
    private ObjectNode transferPayload() throws Exception {
        return (ObjectNode) objectMapper.readTree(
                "{\"m04OrderNo\":\"M20261002001\",\"visitId\":\"I2026100200001\",\"patientId\":\"700101\","
                        + "\"transferType\":\"drug\",\"firstTransferredAt\":\"2026-10-02T01:30:00Z\"}");
    }

    /** 计划路基线载荷（可变副本供守卫用例裁剪字段）。 */
    private ObjectNode planPayload() throws Exception {
        return (ObjectNode) objectMapper.readTree(
                "{\"m04OrderNo\":\"M20261002001\",\"visitId\":\"I2026100200001\",\"patientId\":\"700101\","
                        + "\"planDate\":\"2026-10-03\",\"planNos\":[\"PL2026100300001\",\"PL2026100300002\"],"
                        + "\"planTimes\":[\"08:00\",\"09:00\"]}");
    }

    /** 断言入口方法 @RabbitListener 队列名与治理推导名（q.nursing.<登记名>）逐字一致。 */
    private static void assertQueue(String method, String expectedQueue) throws Exception {
        RabbitListener annotation = InpatientOrderEventListener.class
                .getMethod(method, Message.class)
                .getAnnotation(RabbitListener.class);
        assertThat(annotation).as("入口方法 %s 须挂 @RabbitListener", method).isNotNull();
        assertThat(annotation.queues()).containsExactly(expectedQueue);
    }
}

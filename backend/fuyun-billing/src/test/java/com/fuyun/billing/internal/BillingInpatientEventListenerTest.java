package com.fuyun.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.config.BillingMessagingConfig;
import com.fuyun.billing.service.IInpatientChargeService;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 住院域事件消费单测（P2 PR-1 Task 13，修复环 R1 补三终态收敛面）：两通配队列六事件派发透传
 * （visit 三事件/order 三事件）+ cancelled/revoked/audit-rejected 三终态截断派发（共用 stopped
 * 同款截断入口）、audited 无业务动作留痕（冻结载荷不携 items——计价数据面在 created）、未知类型
 * 零业务触达、不合规帧守卫死信留痕、绑定键双锚（@RabbitListener 队列名注解反射 + 自声明 Bean
 * 通配绑定形态）。driveHandler 打桩模板照 BillingChargeEventListenerTest 同款（绕开三段式只测业务派发）。
 */
class BillingInpatientEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private IdempotentConsumerSupport consumerSupport;

    private IInpatientChargeService inpatientChargeService;

    private BillingInpatientEventListener listener;

    @BeforeEach
    void setUp() {
        consumerSupport = mock(IdempotentConsumerSupport.class);
        inpatientChargeService = mock(IInpatientChargeService.class);
        listener = new BillingInpatientEventListener(consumerSupport, inpatientChargeService);
    }

    /** 打桩模板：把交付 handler 取出直接以给定信封执行（绕开三段式，只测业务派发）。 */
    @SuppressWarnings("unchecked")
    private void driveHandler(EventEnvelope envelope, boolean visitEntry) throws Exception {
        doAnswer(inv -> {
                    ((Consumer<EventEnvelope>) inv.getArgument(1)).accept(envelope);
                    return null;
                })
                .when(consumerSupport)
                .consume(any(Message.class), any(Consumer.class));
        if (visitEntry) {
            listener.onVisitEvent(raw());
        } else {
            listener.onOrderEvent(raw());
        }
    }

    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new EventEnvelope(
                "e-1", Instant.now(), "inpatient", eventType, "1", "t-1", mapper.readTree(payloadJson));
    }

    private Message raw() {
        return new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    @DisplayName("admitted 派发透传：visitId/patientId/wardId/admittedAt 逐字段进起费锚点业务体")
    void admittedDispatchesAllFields() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.visit.admitted",
                        "{\"visitId\":\"I20260925000001\",\"patientId\":7,\"wardId\":\"W-NEURO\",\"bedId\":12,"
                                + "\"admittedAt\":\"2026-09-25T01:00:00Z\",\"nursingLevel\":\"NORMAL\"}"),
                true);

        verify(inpatientChargeService)
                .onVisitAdmitted("I20260925000001", 7L, "W-NEURO", Instant.parse("2026-09-25T01:00:00Z"));
    }

    @Test
    @DisplayName("transferred 派发透传：from/to 病区与转移时点逐字段进切分业务体")
    void transferredDispatchesAllFields() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.visit.transferred",
                        "{\"visitId\":\"I20260925000001\",\"patientId\":7,\"fromWardId\":\"W-NEURO\",\"fromBedId\":12,"
                                + "\"toWardId\":\"W-CARDIO\",\"toBedId\":30,\"transferredAt\":\"2026-09-25T02:00:00Z\"}"),
                true);

        verify(inpatientChargeService)
                .onVisitTransferred(
                        "I20260925000001", 7L, "W-NEURO", "W-CARDIO", Instant.parse("2026-09-25T02:00:00Z"));
    }

    @Test
    @DisplayName("discharge-requested 派发透传：visitId/patientId/requestedAt 进停费标记业务体")
    void dischargeRequestedDispatchesAllFields() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.visit.discharge-requested",
                        "{\"visitId\":\"I20260925000001\",\"patientId\":7,\"requestedAt\":\"2026-09-25T03:00:00Z\"}"),
                true);

        verify(inpatientChargeService)
                .onDischargeRequested("I20260925000001", 7L, Instant.parse("2026-09-25T03:00:00Z"));
    }

    @Test
    @DisplayName("created 派发透传：items 数组整段进离散计价业务体（两项）")
    void orderCreatedDispatchesItems() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.order.created",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"orderType\":\"lab\",\"orderClass\":\"STAT\",\"items\":[{\"itemCode\":\"LAB_CBC\","
                                + "\"quantity\":\"2\"},{\"itemCode\":\"EXM_ChestX\",\"quantity\":1}]}"),
                false);

        ArgumentCaptor<JsonNode> itemsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(inpatientChargeService)
                .onOrderCreated(
                        ArgumentMatchers.eq("MO2026092500001"),
                        ArgumentMatchers.eq("I20260925000001"),
                        ArgumentMatchers.eq(7L),
                        itemsCaptor.capture());
        assertThat(itemsCaptor.getValue().isArray()).isTrue();
        assertThat(itemsCaptor.getValue()).hasSize(2);
        assertThat(itemsCaptor.getValue().get(0).path("itemCode").asText()).isEqualTo("LAB_CBC");
    }

    @Test
    @DisplayName("executed/stopped 派发透传：医嘱号+就诊号进确认/截断业务体")
    void executedAndStoppedDispatchOrderKeys() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.order.executed",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"planNo\":\"PL2026092500001\",\"executedAt\":\"2026-09-25T04:00:00Z\"}"),
                false);
        driveHandler(
                envelope(
                        "inpatient.order.stopped",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"stoppedAt\":\"2026-09-25T05:00:00Z\",\"stopOperator\":\"EMP-1\",\"stopReason\":\"转科\"}"),
                false);

        verify(inpatientChargeService).onOrderExecuted("MO2026092500001", "I20260925000001");
        verify(inpatientChargeService).onOrderStopped("MO2026092500001", "I20260925000001");
    }

    @Test
    @DisplayName("audited 到店零业务动作（冻结载荷不携 items，计价数据面在 created——适配留痕直返）")
    void auditedHasNoBusinessAction() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.order.audited.drug",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"auditType\":\"PHARMACIST\",\"auditOperator\":\"EMP-9\",\"auditedAt\":\"2026-09-25T06:00:00Z\"}"),
                false);

        verifyNoInteractions(inpatientChargeService);
    }

    @Test
    @DisplayName("created 子键帧归一派发：eventType=inpatient.order.created.drug 剥离子键进离散计价业务体（Task 16 IT 实测修正）")
    void orderCreatedWithDrugSubKeyDispatchesPricing() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.order.created.drug",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"orderType\":\"drug\",\"orderClass\":\"STAT\",\"items\":[{\"itemCode\":\"DRUG-001\","
                                + "\"quantity\":\"1\"}]}"),
                false);

        verify(inpatientChargeService)
                .onOrderCreated(
                        ArgumentMatchers.eq("MO2026092500001"),
                        ArgumentMatchers.eq("I20260925000001"),
                        ArgumentMatchers.eq(7L),
                        any(JsonNode.class));
    }

    @Test
    @DisplayName("stopped 子键族外帧不受归一影响：无子键 stopped 照常截断；order-plan.generated 未纳管直返")
    void nonSubKeyFamiliesUnaffectedByNormalization() throws Exception {
        driveHandler(
                envelope(
                        "inpatient.order.stopped",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"stoppedAt\":\"2026-09-25T05:00:00Z\",\"stopOperator\":\"EMP-1\",\"stopReason\":\"转科\"}"),
                false);
        driveHandler(envelope("inpatient.order-plan.generated", "{\"m04OrderNo\":\"MO2026092500001\"}"), false);

        verify(inpatientChargeService).onOrderStopped("MO2026092500001", "I20260925000001");
        verify(inpatientChargeService, never()).onOrderCreated(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("通配队列未纳管类型零业务触达（registered/order.transferred 等族帧 info 直返）")
    void unknownWildcardFramesBypassBusiness() throws Exception {
        driveHandler(envelope("inpatient.visit.registered", "{\"visitId\":\"I20260925000001\"}"), true);
        driveHandler(envelope("inpatient.order.transferred", "{\"visitId\":\"I20260925000001\"}"), false);

        verifyNoInteractions(inpatientChargeService);
    }

    @Test
    @DisplayName("cancelled/revoked/audit-rejected 三终态事件派发截断：共用 stopped 同款截断入口（R1 补收敛）")
    void terminalOrderEventsDispatchTruncation() throws Exception {
        // 三终态载荷照 V800 id 45/46、V901 id 67 冻结契约子集（m04OrderNo/visitId 定位键）
        driveHandler(
                envelope(
                        "inpatient.order.cancelled",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"cancelledAt\":\"2026-09-25T07:00:00Z\",\"cancelReason\":\"开错医嘱\"}"),
                false);
        driveHandler(
                envelope(
                        "inpatient.order.revoked",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"revokedAt\":\"2026-09-25T08:00:00Z\"}"),
                false);
        driveHandler(
                envelope(
                        "inpatient.order.audit-rejected",
                        "{\"m04OrderNo\":\"MO2026092500001\",\"visitId\":\"I20260925000001\",\"patientId\":7,"
                                + "\"rejectReason\":\"用法不适宜\",\"rejectedAt\":\"2026-09-25T09:00:00Z\"}"),
                false);

        // 三事件同落截断入口（PENDING→CANCELLED 作废收敛），未纳管派发面零旁路
        verify(inpatientChargeService, times(3)).onOrderStopped("MO2026092500001", "I20260925000001");
    }

    @Test
    @DisplayName("不合规帧守卫：缺 visitId/patientId 非法/缺 wardId/时点非法——死信留痕零业务触达")
    void malformedVisitFramesThrow() throws Exception {
        assertThatThrownBy(() -> driveHandler(envelope("inpatient.visit.admitted", "{\"patientId\":7}"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visitId");
        assertThatThrownBy(() -> driveHandler(
                        envelope(
                                "inpatient.visit.admitted",
                                "{\"visitId\":\"I20260925000001\",\"wardId\":\"W\",\"admittedAt\":\"2026-09-25T01:00:00Z\"}"),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patientId");
        assertThatThrownBy(() -> driveHandler(
                        envelope("inpatient.visit.admitted", "{\"visitId\":\"I20260925000001\",\"patientId\":7}"),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wardId");
        assertThatThrownBy(() -> driveHandler(
                        envelope(
                                "inpatient.visit.admitted",
                                "{\"visitId\":\"I20260925000001\",\"patientId\":7,\"wardId\":\"W\",\"admittedAt\":\"yesterday\"}"),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admittedAt");
        verifyNoInteractions(inpatientChargeService);
    }

    @Test
    @DisplayName("不合规帧守卫：items 非数组定性不合规（离散计价数据面缺失禁静默）")
    void malformedItemsThrow() throws Exception {
        assertThatThrownBy(() -> driveHandler(
                        envelope(
                                "inpatient.order.created",
                                "{\"m04OrderNo\":\"MO-1\",\"visitId\":\"I20260925000001\",\"patientId\":7,\"items\":\"LAB_CBC\"}"),
                        false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("items");
        verifyNoInteractions(inpatientChargeService);
    }

    @Test
    @DisplayName("双入口仅做 consume 委托（三段式标准形态，业务体不重复触达）")
    void listenersDelegateToConsumerSupport() {
        BillingInpatientEventListener fresh =
                new BillingInpatientEventListener(consumerSupport, inpatientChargeService);

        fresh.onVisitEvent(raw());
        fresh.onOrderEvent(raw());

        verify(consumerSupport, times(2)).consume(any(Message.class), any());
    }

    @Test
    @DisplayName("绑定键双锚：两监听器分别绑 q.billing.inpatient.visit.# 与 q.billing.inpatient.order.#（治理命名约定）")
    void listenersBindWildcardQueues() throws Exception {
        var visitMethod = BillingInpatientEventListener.class.getMethod("onVisitEvent", Message.class);
        var orderMethod = BillingInpatientEventListener.class.getMethod("onOrderEvent", Message.class);

        assertThat(visitMethod.getAnnotation(RabbitListener.class).queues())
                .containsExactly("q.billing.inpatient.visit.#");
        assertThat(orderMethod.getAnnotation(RabbitListener.class).queues())
                .containsExactly("q.billing.inpatient.order.#");
    }

    @Test
    @DisplayName("自声明 Bean 双锚：通配绑定 fy.topic + quorum + fy.dlx 与治理形态同款（Task 12 先例）")
    void wildcardQueuesDeclaredWithGovernanceShape() {
        Declarables declarables = new BillingMessagingConfig().billingInpatientWildcardQueues();

        var queues = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .toList();
        var bindings = declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .toList();

        assertThat(queues).hasSize(2);
        assertThat(queues)
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder("q.billing.inpatient.visit.#", "q.billing.inpatient.order.#");
        assertThat(queues).allSatisfy(q -> assertThat(q.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", "fy.dlx"));
        assertThat(bindings).hasSize(2);
        assertThat(bindings).allSatisfy(b -> assertThat(b.getExchange()).isEqualTo("fy.topic"));
        assertThat(bindings)
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("inpatient.visit.#", "inpatient.order.#");
        assertThat(bindings)
                .extracting(Binding::getDestination)
                .containsExactlyInAnyOrder("q.billing.inpatient.visit.#", "q.billing.inpatient.order.#");
    }
}

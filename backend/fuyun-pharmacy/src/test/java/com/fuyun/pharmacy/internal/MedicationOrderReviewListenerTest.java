package com.fuyun.pharmacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.pharmacy.config.PharmacyMessagingConfig;
import com.fuyun.pharmacy.service.IMedicationReviewService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * 住院医嘱开立监听器单测（P2 PR-1 Task 12，brief 冻结用例①②）：drug 子键载荷解析透传、
 * 不合规帧死信守卫、精确绑定键断言（非 drug 子键由绑定面天然隔离——监听器队列注解与自声明
 * Bean 双锚断言绑定键，无业务侧 orderType 复判面）。业务体为包级 handle 方法，@RabbitListener
 * 入口仅做 consume 委托（PharmacyChargedOrderListenerTest 同款单测形态）。
 */
@ExtendWith(MockitoExtension.class)
class MedicationOrderReviewListenerTest {

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IMedicationReviewService medicationReviewService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 信封手工构造（本域监听器单测同款形态）：eventType 携 drug 子键（V901 id 66 登记名+子键） */
    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(
                "ph-ev-1",
                Instant.now(Clock.systemUTC()),
                "inpatient",
                "inpatient.order.created.drug",
                "1",
                "it-trace",
                payload);
    }

    /** 载荷构造（V901 id 66 冻结组件：patientId 经全局 Long→String 以字符串承载） */
    private JsonNode orderCreatedPayload(ObjectMapper mapper) throws Exception {
        return mapper.readTree("""
                {"m04OrderNo":"M20260925001","visitId":"I2026092500001","patientId":"700101",
                 "orderType":"drug","orderClass":"LONG","standbyFlag":false,"groupNo":null,
                 "freqCode":"qd","items":[
                   {"itemSeq":1,"itemCode":"D-IT-001","itemName":"头孢呋辛酯片","dosage":"0.5g",
                    "unit":"g","route":"口服","quantity":"12","itemType":"DRUG"}]}
                """);
    }

    @Test
    @DisplayName("① drug 子键消费落库：载荷冻结组件解析透传（快照整段 JSON 含药品/剂量/途径/数量）")
    void handleOrderCreatedParsesFrozenPayloadToService() throws Exception {
        MedicationOrderReviewListener listener =
                new MedicationOrderReviewListener(consumerSupport, medicationReviewService);

        listener.handleOrderCreated(envelope(orderCreatedPayload(objectMapper)));

        // freqCode 长期医嘱非空；items 整段 JSON 透传（快照唯一数据源）
        verify(medicationReviewService)
                .onOrderCreated(eq("M20260925001"), eq("I2026092500001"), eq(700101L), eq("qd"), anyString());
    }

    @Test
    @DisplayName("①补 临时医嘱 freqCode 可空透传（V901 id 66 契约：freqCode 临时医嘱为 null）")
    void handleOrderCreatedToleratesNullFreqCode() throws Exception {
        MedicationOrderReviewListener listener =
                new MedicationOrderReviewListener(consumerSupport, medicationReviewService);
        ObjectNode payload = jsonPayload();
        payload.putNull("freqCode");

        listener.handleOrderCreated(envelope(payload));

        verify(medicationReviewService).onOrderCreated(anyString(), anyString(), anyLong(), isNull(), anyString());
    }

    @Test
    @DisplayName("①守卫 缺 m04OrderNo 不合规帧显式抛出进死信留痕（V901 id 66 冻结契约）")
    void orderCreatedMissingM04OrderNoRejected() throws Exception {
        MedicationOrderReviewListener listener =
                new MedicationOrderReviewListener(consumerSupport, medicationReviewService);
        ObjectNode payload = jsonPayload();
        payload.remove("m04OrderNo");

        assertThatThrownBy(() -> listener.handleOrderCreated(envelope(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不合规");
        verifyNoInteractions(medicationReviewService);
    }

    @Test
    @DisplayName("①守卫 缺 visitId / patientId 非法 / items 非数组三面不合规帧守卫")
    void orderCreatedRejectsMalformedFrames() throws Exception {
        MedicationOrderReviewListener listener =
                new MedicationOrderReviewListener(consumerSupport, medicationReviewService);

        ObjectNode missingVisit = jsonPayload();
        missingVisit.remove("visitId");
        assertThatThrownBy(() -> listener.handleOrderCreated(envelope(missingVisit)))
                .isInstanceOf(IllegalStateException.class);

        ObjectNode badPatient = jsonPayload();
        badPatient.put("patientId", "not-a-number");
        assertThatThrownBy(() -> listener.handleOrderCreated(envelope(badPatient)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patientId");

        ObjectNode badItems = jsonPayload();
        badItems.put("items", "D-IT-001");
        assertThatThrownBy(() -> listener.handleOrderCreated(envelope(badItems)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("items");
        verifyNoInteractions(medicationReviewService);
    }

    @Test
    @DisplayName("② 绑定键断言：监听器仅绑 drug 子键队列（非 drug 子键由绑定面天然隔离不入本队列）")
    void listenerBindsExactDrugSubkeyQueue() throws Exception {
        // 锚一：@RabbitListener 队列名 = q.<module>.<登记名.drug 子键>（治理命名约定）
        var method = MedicationOrderReviewListener.class.getMethod(
                "onOrderCreated", org.springframework.amqp.core.Message.class);
        RabbitListener annotation = method.getAnnotation(RabbitListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.queues()).containsExactly("q.pharmacy.inpatient.order.created.drug");

        // 锚二：自声明 Bean 绑定键精确等于 drug 子键（quorum + fy.dlx + fy.topic 与治理形态同款）
        Declarables declarables = new PharmacyMessagingConfig().pharmacyInpatientOrderCreatedDrugQueue();
        Queue queue = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .findFirst()
                .orElseThrow();
        Binding binding = declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(queue.getName()).isEqualTo("q.pharmacy.inpatient.order.created.drug");
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", "fy.dlx");
        assertThat(binding.getExchange()).isEqualTo("fy.topic");
        assertThat(binding.getRoutingKey()).isEqualTo("inpatient.order.created.drug");
        assertThat(binding.getDestination()).isEqualTo("q.pharmacy.inpatient.order.created.drug");
    }

    @Test
    @DisplayName("@RabbitListener 入口仅做 consume 委托（三段式标准形态，业务体不重复触达）")
    void onOrderCreatedDelegatesToConsumerSupport() {
        MedicationOrderReviewListener listener =
                new MedicationOrderReviewListener(consumerSupport, medicationReviewService);

        listener.onOrderCreated(new org.springframework.amqp.core.Message(
                new byte[0], new org.springframework.amqp.core.MessageProperties()));

        verify(consumerSupport).consume(any(org.springframework.amqp.core.Message.class), any());
    }

    /** 基线载荷构造（可变副本供各守卫用例裁剪字段） */
    private ObjectNode jsonPayload() throws Exception {
        return (ObjectNode) orderCreatedPayload(objectMapper);
    }
}

package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 设备呼叫转发监听器单测（Task 11）：合规载荷→CALL_TRIGGERED 帧发布（路由病区=iot 数字串、
 * 载荷六字段镜像、occurredAt=触发时刻）；定位键缺失（callNo/wardId/triggeredAt）ISE 死信、
 * wardId 非数字 ISE、时点非法 ISE；bedId 缺位可空承载；消费入口三段式委托。
 */
@ExtendWith(MockitoExtension.class)
class IotCallTriggeredListenerTest {

    /** iot 域病区 id（数字串路由断言基准——标识空间申报见监听器 javadoc） */
    private static final long IOT_WARD_ID = 88001L;

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private Message message;

    private IotCallTriggeredListener listener;

    @BeforeEach
    void setUp() {
        listener = new IotCallTriggeredListener(consumerSupport, events);
    }

    @Test
    @DisplayName("合规载荷转发：CALL_TRIGGERED 帧（路由=iot 病区数字串、六字段镜像、occurredAt=触发时刻）")
    void forwardsCallTriggeredToBoardWithDigitalWardRouting() {
        Instant triggeredAt = Instant.parse("2026-10-02T08:00:00Z");

        listener.handleCallTriggered(envelope(callPayload(triggeredAt)));

        ArgumentCaptor<NurseBoardPushEvent> eventCaptor = ArgumentCaptor.forClass(NurseBoardPushEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        NurseBoardPushEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo(NurseBoardPushFrame.TYPE_CALL_TRIGGERED);
        // 路由病区=iot 数字串（Long.toString——与 board 其余四类帧的护理编码路由分属两标识空间）
        assertThat(event.wardId()).isEqualTo("88001");
        NurseBoardPushFrame.CallTriggeredPayload payload = (NurseBoardPushFrame.CallTriggeredPayload) event.payload();
        assertThat(payload.callNo()).isEqualTo("CALL2026100200001");
        assertThat(payload.deviceId()).isEqualTo("CALL-EXT-01");
        assertThat(payload.callType()).isEqualTo("EMERGENCY");
        assertThat(payload.bedId()).isEqualTo(1201L);
        assertThat(payload.wardId()).isEqualTo(IOT_WARD_ID);
        assertThat(payload.triggeredAt()).isEqualTo(triggeredAt);
        assertThat(event.occurredAt()).isEqualTo(triggeredAt);
    }

    @Test
    @DisplayName("bedId 缺位可空承载（IoTDA 直发路径床号可缺——转发面不强制）")
    void toleratesMissingBedIdAsNull() {
        ObjectNode payload = callPayload(Instant.parse("2026-10-02T08:05:00Z"));
        payload.remove("bedId");

        listener.handleCallTriggered(envelope(payload));

        ArgumentCaptor<NurseBoardPushEvent> eventCaptor = ArgumentCaptor.forClass(NurseBoardPushEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((NurseBoardPushFrame.CallTriggeredPayload)
                                eventCaptor.getValue().payload())
                        .bedId())
                .isNull();
    }

    @Test
    @DisplayName("定位键缺失：callNo/wardId/triggeredAt 任一缺失 ISE 死信留痕且零推送")
    void rejectsMissingLocatorFields() {
        ObjectNode noCallNo = callPayload(Instant.now());
        noCallNo.remove("callNo");
        assertDeadLetter(noCallNo);

        ObjectNode noWard = callPayload(Instant.now());
        noWard.remove("wardId");
        assertDeadLetter(noWard);

        ObjectNode noTime = callPayload(Instant.now());
        noTime.remove("triggeredAt");
        assertDeadLetter(noTime);
    }

    @Test
    @DisplayName("wardId 非数字标识：ISE 死信留痕（iot 域病区 id 为 BIGINT——契约守卫）")
    void rejectsNonNumericWardId() {
        ObjectNode payload = callPayload(Instant.now());
        payload.put("wardId", "W01");

        assertThatThrownBy(() -> listener.handleCallTriggered(envelope(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wardId");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("triggeredAt 非法时点文本：ISE 死信留痕（ISO-8601 契约守卫）")
    void rejectsMalformedTriggeredAt() {
        ObjectNode payload = callPayload(Instant.now());
        payload.put("triggeredAt", "2026/10/02 08:00");

        assertThatThrownBy(() -> listener.handleCallTriggered(envelope(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triggeredAt");
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("消费入口：标准三段式委托 IdempotentConsumerSupport（eventId 幂等面）")
    void consumeEntryDelegatesToConsumerSupport() {
        listener.onCallTriggered(message);

        // 三段式委托（解析信封 → 幂等范式 → 业务 + 成功登记）；业务体真验归包级直驱用例
        verify(consumerSupport).consume(any(Message.class), any());
    }

    // ===================== 测试数据与断言辅助 =====================

    /**
     * 缺失定位键断言（三字段共用：ISE 死信 + 零推送）。
     *
     * @param payload 破损载荷替身，非空
     */
    private void assertDeadLetter(ObjectNode payload) {
        assertThatThrownBy(() -> listener.handleCallTriggered(envelope(payload)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    /**
     * 合规载荷替身（V1004 id 81 冻结六字段——数字型 wardId/bedId 与生产契约同形）。
     *
     * @param triggeredAt 触发时刻
     * @return 载荷 JSON，非空
     */
    private static ObjectNode callPayload(Instant triggeredAt) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("callNo", "CALL2026100200001");
        payload.put("deviceId", "CALL-EXT-01");
        payload.put("callType", "EMERGENCY");
        payload.put("bedId", 1201L);
        payload.put("wardId", IOT_WARD_ID);
        payload.put("triggeredAt", triggeredAt.toString());
        return payload;
    }

    /**
     * 信封手工构造（eventType=登记名 iot.call.triggered，producer=iot）。
     *
     * @param payload 载荷 JSON，非空
     * @return 事件信封，非空
     */
    private static EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(
                "nurs-ev-call",
                Instant.now(),
                "iot",
                NursingMessagingConstants.EVENT_SUB_IOT_CALL_TRIGGERED,
                "1",
                null,
                payload);
    }
}

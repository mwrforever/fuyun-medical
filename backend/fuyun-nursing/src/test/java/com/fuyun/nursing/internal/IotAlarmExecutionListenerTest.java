package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.service.IInfusionService;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;

/**
 * IoT 告警执行单挂接监听器单测（Task 6 brief Step 2 冻结组）：triggered 匹配升级委托、
 * 无患者锚跳过（零副作用）、closed 复位委托、escalated 同款累计委托、不合规帧死信守卫与
 * GC15 SYSTEM 桥接（入口落位/finally 清理）。消费体幂等三态（CAS 零行/锚反查）归
 * InfusionServiceImplTest 用例承载（本类只测薄壳路由）。
 */
@ExtendWith(MockitoExtension.class)
class IotAlarmExecutionListenerTest {

    /** 告警触发事件类型（V1004 id 74 注册面） */
    private static final String EVENT_TRIGGERED = "iot.alarm.triggered";

    /** 告警升级事件类型（V1004 id 75 注册面） */
    private static final String EVENT_ESCALATED = "iot.alarm.escalated";

    /** 告警关闭事件类型（V1004 id 76 注册面） */
    private static final String EVENT_CLOSED = "iot.alarm.closed";

    /** 告警业务号（挂单锚断言基准） */
    private static final String ALARM_NO = "AL2026100200001";

    /** 患者主索引（直配定位键） */
    private static final long PATIENT = 7L;

    /** 消费队列命名前缀（治理构件推导锚） */
    private static final String QUEUE_PREFIX = "q.nursing.";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IInfusionService infusionService;

    @Mock
    private Message message;

    private final ObjectMapper mapper = new ObjectMapper();

    private IotAlarmExecutionListener listener;

    @AfterEach
    void clearOperator() {
        // 防御性清理：SYSTEM 桥接残留防线程复用串号
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("① triggered 匹配升级：patientId 直配委托输液域（alarmNo 透传）")
    void triggeredDelegatesPatientDimensionEscalation() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);

        listener.handleAlarmTriggered(envelope(EVENT_TRIGGERED, """
                {"alarmNo":"%s","deviceId":"it-dev-001","patientId":%d,"visitId":"I2026100200001",
                 "wardId":5,"alarmLevel":"CRITICAL","metricCode":"INFUSION_SHORTAGE"}
                """.formatted(ALARM_NO, PATIENT)));

        verify(infusionService).escalateOnAlarmTriggered(PATIENT, ALARM_NO);
    }

    @Test
    @DisplayName("② triggered 无患者锚（公共区域设备告警）：info 跳过零委托（零副作用）")
    void triggeredWithoutPatientAnchorSkipsDelegation() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);

        assertThatCode(() -> listener.handleAlarmTriggered(envelope(
                        EVENT_TRIGGERED,
                        "{\"alarmNo\":\"%s\",\"deviceId\":\"it-dev-001\",\"patientId\":null}".formatted(ALARM_NO))))
                .doesNotThrowAnyException();

        verifyNoInteractions(infusionService);
    }

    @Test
    @DisplayName("③ closed 复位：alarmNo 锚委托挂接锚复位（escalation_count 保留追溯）")
    void closedDelegatesAnchorReset() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);

        listener.handleAlarmClosed(envelope(
                EVENT_CLOSED,
                "{\"alarmNo\":\"%s\",\"deviceId\":\"it-dev-001\",\"wardId\":5,\"closedBy\":\"nurse-01\"}"
                        .formatted(ALARM_NO)));

        verify(infusionService).resetAlarmClosed(ALARM_NO);
    }

    @Test
    @DisplayName("④ escalated 同款累计：alarmNo 反查锚委托（载荷无 patientId）")
    void escalatedDelegatesAnchorAccumulation() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);

        listener.handleAlarmEscalated(envelope(
                EVENT_ESCALATED,
                "{\"alarmNo\":\"%s\",\"deviceId\":\"it-dev-001\",\"wardId\":5,\"escalationLevel\":2}"
                        .formatted(ALARM_NO)));

        verify(infusionService).escalateOnAlarmEscalated(ALARM_NO);
    }

    @Test
    @DisplayName("不合规帧守卫：三路缺 alarmNo ISE 死信留痕（禁静默吞）")
    void malformedPayloadsAreRejectedToDeadLetter() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);

        assertThatThrownBy(() -> listener.handleAlarmTriggered(
                        envelope(EVENT_TRIGGERED, "{\"deviceId\":\"it-dev-001\",\"patientId\":7}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alarmNo");
        assertThatThrownBy(() -> listener.handleAlarmEscalated(envelope(EVENT_ESCALATED, "{\"wardId\":5}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alarmNo");
        assertThatThrownBy(() -> listener.handleAlarmClosed(envelope(EVENT_CLOSED, "{\"closedBy\":\"x\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alarmNo");

        verifyNoInteractions(infusionService);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：三入口落位 SYSTEM、finally 清理防串号")
    void consumptionBridgeSetsAndClearsSystemOperator() {
        listener = new IotAlarmExecutionListener(consumerSupport, infusionService);
        AtomicReference<String> operatorInHandler = new AtomicReference<>();
        doAnswer(invocation -> {
                    operatorInHandler.set(OperatorContextHolder.get());
                    return null;
                })
                .when(consumerSupport)
                .consume(any(), any());

        listener.onAlarmTriggered(message);
        listener.onAlarmEscalated(message);
        listener.onAlarmClosed(message);

        assertThat(operatorInHandler.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
        verify(consumerSupport, org.mockito.Mockito.times(3)).consume(any(), any());
    }

    @Test
    @DisplayName("队列绑定锚：三入口绑定 q.nursing.iot.alarm.{triggered/escalated/closed}（治理推导名一致）")
    void listenerBindsToGovernedQueueNames() throws NoSuchMethodException {
        org.springframework.amqp.rabbit.annotation.RabbitListener triggered = IotAlarmExecutionListener.class
                .getMethod("onAlarmTriggered", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        org.springframework.amqp.rabbit.annotation.RabbitListener escalated = IotAlarmExecutionListener.class
                .getMethod("onAlarmEscalated", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        org.springframework.amqp.rabbit.annotation.RabbitListener closed = IotAlarmExecutionListener.class
                .getMethod("onAlarmClosed", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);

        assertThat(triggered).as("触发入口必须挂 @RabbitListener").isNotNull();
        assertThat(triggered.queues()).containsExactly(QUEUE_PREFIX + EVENT_TRIGGERED);
        assertThat(escalated).as("升级入口必须挂 @RabbitListener").isNotNull();
        assertThat(escalated.queues()).containsExactly(QUEUE_PREFIX + EVENT_ESCALATED);
        assertThat(closed).as("关闭入口必须挂 @RabbitListener").isNotNull();
        assertThat(closed.queues()).containsExactly(QUEUE_PREFIX + EVENT_CLOSED);
    }

    /**
     * 构造事件信封（payload JSON 文本形态——消费侧契约以 JsonNode 读）。
     *
     * @param eventType 事件类型字面量，非空
     * @param json      载荷 JSON 原文，非空
     * @return 事件信封，非空
     */
    private EventEnvelope envelope(String eventType, String json) {
        try {
            return new EventEnvelope("ev-1", Instant.now(), "iot", eventType, "v1", null, mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试载荷构造失败", e);
        }
    }
}

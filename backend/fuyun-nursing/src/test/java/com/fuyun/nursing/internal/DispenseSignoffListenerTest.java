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
import com.fuyun.nursing.service.IOrderExecutionOperateService;
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
 * 摆药签收衔接监听器单测（Task 5 brief 冻结组）：住院载荷委托（traceCodes 首位推导）、
 * 门诊行忽略、行摘要回退、不合规帧死信守卫与 GC15 SYSTEM 桥接（入口落位/finally 清理）。
 * 消费体幂等三态（签收零行/升格零行/挂接 DO NOTHING）归操作域用例承载（本类只测薄壳路由）。
 */
@ExtendWith(MockitoExtension.class)
class DispenseSignoffListenerTest {

    /** 事件信封生产者（载荷契约锚——id 28 注册面） */
    private static final String EVENT_TYPE = "pharmacy.dispense.completed";

    /** 消费队列名（治理构件按 q.<module>.<eventType> 推导——绑定锚断言基准） */
    private static final String QUEUE_NAME = "q.nursing.pharmacy.dispense.completed";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IOrderExecutionOperateService operateService;

    @Mock
    private Message message;

    private final ObjectMapper mapper = new ObjectMapper();

    private DispenseSignoffListener listener;

    @AfterEach
    void clearOperator() {
        // 防御性清理：SYSTEM 桥接残留防线程复用串号
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("住院载荷委托：traceCodes 首位推导袋签码透传操作域（m04OrderNo/dispenseType/dispensePlanNo）")
    void inpatientPayloadDelegatesWithFirstTraceCode() {
        listener = new DispenseSignoffListener(consumerSupport, operateService);
        EventEnvelope envelope = envelope("""
                {"dispenseNo":"D20261002001","dispenseType":"INPATIENT_PIVA","m04OrderNo":"M20261002001",
                 "wardId":"W01","dispensePlanNo":"DP2026100200001",
                 "lines":[{"itemCode":"D0001","batchNo":"B1","quantity":"1","traceCodes":["BAG2026100201","BAG2026100202"]}]}
                """);

        listener.handleDispenseCompleted(envelope);

        verify(operateService)
                .onDispenseCompleted("M20261002001", "INPATIENT_PIVA", "DP2026100200001", "BAG2026100201");
    }

    @Test
    @DisplayName("门诊行忽略：m04OrderNo 缺失（null）直接确认零委托（V1111 双语义承载）")
    void outpatientPayloadIsIgnoredWithoutDelegation() {
        listener = new DispenseSignoffListener(consumerSupport, operateService);
        EventEnvelope envelope =
                envelope("{\"dispenseNo\":\"D20261002002\",\"dispenseType\":\"OUTPATIENT\",\"m04OrderNo\":null}");

        assertThatCode(() -> listener.handleDispenseCompleted(envelope)).doesNotThrowAnyException();

        verifyNoInteractions(operateService);
    }

    @Test
    @DisplayName("袋签码回退：traceCodes 全空取首行 itemCode#batchNo 摘要（列宽 64 内承载）")
    void bagLabelFallsBackToLineSummary() {
        listener = new DispenseSignoffListener(consumerSupport, operateService);
        EventEnvelope envelope = envelope("""
                {"dispenseNo":"D20261002003","dispenseType":"INPATIENT_PIVA","m04OrderNo":"M20261002002",
                 "dispensePlanNo":"DP2026100200002",
                 "lines":[{"itemCode":"D0009","batchNo":"B9","quantity":"1","traceCodes":[]}]}
                """);

        listener.handleDispenseCompleted(envelope);

        verify(operateService).onDispenseCompleted("M20261002002", "INPATIENT_PIVA", "DP2026100200002", "D0009#B9");
    }

    @Test
    @DisplayName("不合规帧守卫：住院行缺 dispenseType/dispensePlanNo 或无可推导袋签码 ISE 死信留痕")
    void malformedPayloadsAreRejectedToDeadLetter() {
        listener = new DispenseSignoffListener(consumerSupport, operateService);
        // 缺 dispenseType
        assertThatThrownBy(() -> listener.handleDispenseCompleted(
                        envelope("{\"m04OrderNo\":\"M20261002001\",\"dispensePlanNo\":\"DP1\",\"lines\":[]}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispenseType");
        // 缺 dispensePlanNo
        assertThatThrownBy(() -> listener.handleDispenseCompleted(
                        envelope("{\"m04OrderNo\":\"M20261002001\",\"dispenseType\":\"INPATIENT_DOSE\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispensePlanNo");
        // 无行集可推导袋签码
        assertThatThrownBy(
                        () -> listener.handleDispenseCompleted(
                                envelope(
                                        "{\"m04OrderNo\":\"M20261002001\",\"dispenseType\":\"INPATIENT_PIVA\",\"dispensePlanNo\":\"DP1\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("袋签码");
        verifyNoInteractions(operateService);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：消费入口落位 SYSTEM、消费体内在位、finally 清理防串号")
    void consumptionBridgeSetsAndClearsSystemOperator() {
        listener = new DispenseSignoffListener(consumerSupport, operateService);
        // 消费模板直驱业务体（标准三段式第二段），业务体内回读操作者上下文断言 SYSTEM 在位；
        // consume 为 void 方法——doAnswer 形态承载（when() 仅适用非 void）
        AtomicReference<String> operatorInHandler = new AtomicReference<>();
        doAnswer(invocation -> {
                    operatorInHandler.set(OperatorContextHolder.get());
                    return null;
                })
                .when(consumerSupport)
                .consume(any(), any());

        listener.onDispenseCompleted(message);

        assertThat(operatorInHandler.get()).isEqualTo("SYSTEM");
        // finally 清理：消费结束操作者上下文不残留（线程复用串号防线）
        assertThat(OperatorContextHolder.get()).isNull();
        verify(consumerSupport).consume(any(), any());
    }

    @Test
    @DisplayName("队列绑定锚：监听器绑定 q.nursing.pharmacy.dispense.completed（治理推导名一致）")
    void listenerBindsToGovernedQueueName() throws NoSuchMethodException {
        org.springframework.amqp.rabbit.annotation.RabbitListener binding = DispenseSignoffListener.class
                .getMethod("onDispenseCompleted", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertThat(binding).as("消费入口必须挂 @RabbitListener").isNotNull();
        assertThat(binding.queues()).containsExactly(QUEUE_NAME);
    }

    /**
     * 构造事件信封（payload JSON 文本形态——消费侧契约以 JsonNode 读）。
     *
     * @param json 载荷 JSON 原文，非空
     * @return 事件信封，非空
     */
    private EventEnvelope envelope(String json) {
        try {
            return new EventEnvelope("ev-1", Instant.now(), "pharmacy", EVENT_TYPE, "v1", null, mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试载荷构造失败", e);
        }
    }
}

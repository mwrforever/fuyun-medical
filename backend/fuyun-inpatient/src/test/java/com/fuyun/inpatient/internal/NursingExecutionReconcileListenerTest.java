package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.enums.PlanStatus;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 护理执行回执对账监听器单测（Task 5 brief 冻结两路）：对账一致（计划 EXECUTED——debug
 * 留痕零副作用）与对账差异（计划非 EXECUTED/缺行——warn 留痕无 order_audit 落行）；
 * 不合规帧（缺冻结定位字段）ISE 死信留痕。消费队列绑定锚（新队列
 * q.inpatient.nursing.order-execution.completed 声明正确性）一并断言。
 */
@ExtendWith(MockitoExtension.class)
class NursingExecutionReconcileListenerTest {

    /** 事件信封事件类型（V800 id 64 注册面） */
    private static final String EVENT_TYPE = "nursing.order-execution.completed";

    /** 消费队列名（治理构件按 q.<module>.<eventType> 推导——绑定锚断言基准） */
    private static final String QUEUE_NAME = "q.inpatient.nursing.order-execution.completed";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private OrderExecutePlanMapper planMapper;

    private NursingExecutionReconcileListener listener;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecutePlan.class);
    }

    @Test
    @DisplayName("对账一致：计划 EXECUTED 零副作用闭环确认（查询一次即返）")
    void reconciledExecutionPassesSilently() {
        listener = new NursingExecutionReconcileListener(consumerSupport, planMapper);
        when(planMapper.selectOne(any())).thenReturn(planRow(PlanStatus.EXECUTED));

        assertThatCode(() -> listener.handleReconcile(envelope("""
                {"executionNo":"EX2026100200001","m04PlanNo":"PL2026100300001","m04OrderNo":"M20261002001"}
                """))).doesNotThrowAnyException();

        verify(planMapper).selectOne(any());
        verifyNoInteractions(consumerSupport);
    }

    @Test
    @DisplayName("对账差异：计划 PENDING 与缺行两路 warn 留痕（无 order_audit 落行、不阻断）")
    void mismatchedOrMissingPlanKeepsWarnOnlyTrace() {
        listener = new NursingExecutionReconcileListener(consumerSupport, planMapper);
        // 差异一：计划非 EXECUTED（主路径未达窗口——COMPENSATING 补偿承载）
        when(planMapper.selectOne(any())).thenReturn(planRow(PlanStatus.PENDING));
        assertThatCode(() -> listener.handleReconcile(
                        envelope("{\"executionNo\":\"EX2026100200001\",\"m04PlanNo\":\"PL2026100300001\"}")))
                .doesNotThrowAnyException();

        // 差异二：计划缺行（乱序/主路径未达）——同款 warn 留痕口径
        when(planMapper.selectOne(any())).thenReturn(null);
        assertThatCode(() -> listener.handleReconcile(
                        envelope("{\"executionNo\":\"EX2026100200002\",\"m04PlanNo\":\"PL2026100300002\"}")))
                .doesNotThrowAnyException();

        // 两路差异各查询一次（warn 留痕口径——无阻断无反向修复）
        verify(planMapper, times(2)).selectOne(any());
        verifyNoInteractions(consumerSupport);
    }

    @Test
    @DisplayName("队列绑定锚：监听器绑定 q.inpatient.nursing.order-execution.completed（治理推导名一致）")
    void listenerBindsToGovernedQueueName() throws NoSuchMethodException {
        org.springframework.amqp.rabbit.annotation.RabbitListener binding = NursingExecutionReconcileListener.class
                .getMethod("onOrderExecutionCompleted", org.springframework.amqp.core.Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertThat(binding).as("消费入口必须挂 @RabbitListener").isNotNull();
        assertThat(binding.queues()).containsExactly(QUEUE_NAME);
    }

    @Test
    @DisplayName("不合规帧守卫：缺 executionNo/m04PlanNo 定位字段 ISE 死信留痕")
    void malformedPayloadsAreRejectedToDeadLetter() {
        listener = new NursingExecutionReconcileListener(consumerSupport, planMapper);
        // 缺 executionNo
        assertThatThrownBy(() -> listener.handleReconcile(envelope("{\"m04PlanNo\":\"PL2026100300001\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executionNo");
        // 缺 m04PlanNo
        assertThatThrownBy(() -> listener.handleReconcile(envelope("{\"executionNo\":\"EX2026100200001\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("m04PlanNo");
        verifyNoInteractions(planMapper);
        verifyNoInteractions(consumerSupport);
    }

    /**
     * 计划行替身（对账比对取数面）。
     *
     * @param status 计划状态
     * @return 计划行，非空
     */
    private OrderExecutePlan planRow(PlanStatus status) {
        OrderExecutePlan plan = new OrderExecutePlan();
        plan.setPlanNo("PL2026100300001");
        plan.setStatus(status.getCode());
        return plan;
    }

    /**
     * 构造事件信封（payload JSON 文本形态——消费侧契约以 JsonNode 读）。
     *
     * @param json 载荷 JSON 原文，非空
     * @return 事件信封，非空
     */
    private EventEnvelope envelope(String json) {
        try {
            return new EventEnvelope("ev-1", Instant.now(), "nursing", EVENT_TYPE, "v1", null, mapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试载荷构造失败", e);
        }
    }
}

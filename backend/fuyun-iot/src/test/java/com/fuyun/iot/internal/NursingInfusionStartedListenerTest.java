package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;

/**
 * 护理开始输注消费监听器单测（P2 PR-3 Task 6）：BOUND 泵类绑定查询+监测关联留痕（纯读零写）、
 * 无绑定空集分支、不合规帧死信守卫、GC15 SYSTEM 桥接与队列绑定锚。patientId 直配零新表
 * 裁决下的行为面——监听器不落任何映射表（断言零写交互）。
 */
@ExtendWith(MockitoExtension.class)
class NursingInfusionStartedListenerTest {

    /** 事件类型（V800 id 62 注册面） */
    private static final String EVENT_TYPE = "nursing.infusion.started";

    /** 消费队列名（治理构件推导锚——常量类同源声明） */
    private static final String QUEUE_NAME = IotMessagingConstants.QUEUE_NURSING_INFUSION_STARTED;

    /** 执行单号 */
    private static final String EXEC = "EX2026100200001";

    /** 患者主索引 */
    private static final long PATIENT = 7L;

    /** I 型 14 位就诊号 */
    private static final String VISIT = "I2026100200001";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IotBindingMapper bindingMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private Message message;

    private final ObjectMapper mapper = new ObjectMapper();

    private NursingInfusionStartedListener listener;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（绑定+设备档案）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotBindingEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("监测关联建立：BOUND 泵类绑定查询+档案收窄留痕（纯读零写——patientId 直配零新表）")
    void establishesMonitoringAssociationAsPureRead() {
        listener = new NursingInfusionStartedListener(consumerSupport, bindingMapper, deviceMapper);
        when(bindingMapper.selectList(any())).thenReturn(List.of(boundPumpBinding(), boundMonitorBinding()));
        when(deviceMapper.selectList(any())).thenReturn(List.of(pumpDevice()));

        assertThatCode(() -> listener.handleInfusionStarted(envelope("""
                {"executionNo":"%s","patientId":%d,"visitId":"%s","bagLabelCode":"BAG2026100201","startedAt":"2026-10-02T07:00:00Z"}
                """.formatted(EXEC, PATIENT, VISIT))))
                .doesNotThrowAnyException();

        // 绑定查询（患者+BOUND+就诊维收窄）与档案查询各一次（批量装载纪律）
        verify(bindingMapper).selectList(any());
        verify(deviceMapper).selectList(any());
        // 零写面：不插绑定、不建映射表（patientId 直配裁决——关联由 nursing 侧直配承载）
        verifyNoInteractions(consumerSupport);
    }

    @Test
    @DisplayName("无 BOUND 绑定：泵类集空直出（零档案查询——空 IN 防御）")
    void handlesNoBoundBindingWithoutDeviceQuery() {
        listener = new NursingInfusionStartedListener(consumerSupport, bindingMapper, deviceMapper);
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> listener.handleInfusionStarted(
                        envelope("{\"executionNo\":\"%s\",\"patientId\":%d}".formatted(EXEC, PATIENT))))
                .doesNotThrowAnyException();

        verify(bindingMapper).selectList(any());
        org.mockito.Mockito.verifyNoMoreInteractions(deviceMapper);
    }

    @Test
    @DisplayName("不合规帧守卫：缺 executionNo/patientId ISE 死信留痕（禁静默吞）")
    void malformedPayloadsAreRejectedToDeadLetter() {
        listener = new NursingInfusionStartedListener(consumerSupport, bindingMapper, deviceMapper);

        assertThatThrownBy(() -> listener.handleInfusionStarted(envelope("{\"patientId\":%d}".formatted(PATIENT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executionNo");
        assertThatThrownBy(() -> listener.handleInfusionStarted(envelope("{\"executionNo\":\"%s\"}".formatted(EXEC))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patientId");

        verifyNoInteractions(bindingMapper);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：消费入口落位 SYSTEM、finally 清理防串号")
    void consumptionBridgeSetsAndClearsSystemOperator() {
        listener = new NursingInfusionStartedListener(consumerSupport, bindingMapper, deviceMapper);
        AtomicReference<String> operatorInHandler = new AtomicReference<>();
        doAnswer(invocation -> {
                    operatorInHandler.set(OperatorContextHolder.get());
                    return null;
                })
                .when(consumerSupport)
                .consume(any(), any());

        listener.onInfusionStarted(message);

        assertThat(operatorInHandler.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
        verify(consumerSupport).consume(any(), any());
    }

    @Test
    @DisplayName("队列绑定锚：监听器绑定 q.iot.nursing.infusion.started（治理推导名一致）")
    void listenerBindsToGovernedQueueName() throws NoSuchMethodException {
        org.springframework.amqp.rabbit.annotation.RabbitListener binding = NursingInfusionStartedListener.class
                .getMethod("onInfusionStarted", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertThat(binding).as("消费入口必须挂 @RabbitListener").isNotNull();
        assertThat(binding.queues()).containsExactly(QUEUE_NAME);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** BOUND 泵类绑定替身（MOBILE 模式——输液泵随患者移动形态）。 */
    private IotBindingEntity boundPumpBinding() {
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId("pump-001");
        binding.setPatientId(PATIENT);
        binding.setVisitId(VISIT);
        binding.setBindType(BindType.MOBILE);
        binding.setStatus(BindingStatus.BOUND);
        return binding;
    }

    /** BOUND 非泵类绑定替身（监护仪——持续生命体征监测面，泵类收窄后被排除）。 */
    private IotBindingEntity boundMonitorBinding() {
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId("monitor-001");
        binding.setPatientId(PATIENT);
        binding.setVisitId(VISIT);
        binding.setBindType(BindType.FIXED);
        binding.setStatus(BindingStatus.BOUND);
        return binding;
    }

    /** 输液泵设备档案替身（总 Spec 5.1 词表类型）。 */
    private IotDeviceEntity pumpDevice() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId("pump-001");
        device.setDeviceType("INFUSION_PUMP");
        return device;
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

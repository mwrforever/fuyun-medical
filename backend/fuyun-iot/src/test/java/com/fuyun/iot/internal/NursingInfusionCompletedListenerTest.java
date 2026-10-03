package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
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
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.IBindingService;
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
 * 护理拔针/输注结束消费监听器单测（P2 PR-3 Task 6）：患者维度停止监测——BOUND 泵类绑定经
 * 绑定域唯一写入口解绑（「绑定关系监测暂停」标记进既有表列裁决的行为锚）；非泵类绑定不动、
 * 无绑定/无泵类两零命中分支、不合规帧死信守卫、GC15 SYSTEM 桥接与队列绑定锚。
 */
@ExtendWith(MockitoExtension.class)
class NursingInfusionCompletedListenerTest {

    /** 事件类型（V800 id 63 注册面） */
    private static final String EVENT_TYPE = "nursing.infusion.completed";

    /** 消费队列名（治理构件推导锚——常量类同源声明） */
    private static final String QUEUE_NAME = IotMessagingConstants.QUEUE_NURSING_INFUSION_COMPLETED;

    /** 执行单号 */
    private static final String EXEC = "EX2026100200001";

    /** 患者主索引 */
    private static final long PATIENT = 7L;

    /** 拔针驱动解绑原因（unbind_reason 既有列承载——监测暂停标记） */
    private static final String UNBIND_REASON = "INFUSION_COMPLETED";

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private IotBindingMapper bindingMapper;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IBindingService bindingService;

    @Mock
    private Message message;

    private final ObjectMapper mapper = new ObjectMapper();

    private NursingInfusionCompletedListener listener;

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
    @DisplayName("停止监测：泵类绑定解绑（既有表列标记），非泵类绑定不动（生命体征监测面保持）")
    void stopsMonitoringByUnbindingPumpBindingsOnly() {
        listener = new NursingInfusionCompletedListener(consumerSupport, bindingMapper, deviceMapper, bindingService);
        when(bindingMapper.selectList(any())).thenReturn(List.of(pumpBinding(), monitorBinding()));
        when(deviceMapper.selectList(any())).thenReturn(List.of(pumpDevice()));

        assertThatCode(() -> listener.handleInfusionCompleted(envelope("""
                {"executionNo":"%s","patientId":%d,"visitId":"I2026100200001","endedAt":"2026-10-02T08:30:00Z"}
                """.formatted(EXEC, PATIENT))))
                .doesNotThrowAnyException();

        // 泵类设备解绑（绑定域唯一写入口——双 CAS+事件发布承载），原因=拔针驱动固定文案
        verify(bindingService).unbind(eq("pump-001"), eq(new UnbindDeviceRequest(UNBIND_REASON)));
        // 非泵类绑定不动（监护仪持续监测面不随拔针解除）
        verify(bindingService, never()).unbind(eq("monitor-001"), any(UnbindDeviceRequest.class));
    }

    @Test
    @DisplayName("零命中·无 BOUND 绑定：重复投递/未绑泵幂等留痕收口（零解绑零档案查询）")
    void handlesNoBoundBindingIdempotently() {
        listener = new NursingInfusionCompletedListener(consumerSupport, bindingMapper, deviceMapper, bindingService);
        when(bindingMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> listener.handleInfusionCompleted(
                        envelope("{\"executionNo\":\"%s\",\"patientId\":%d}".formatted(EXEC, PATIENT))))
                .doesNotThrowAnyException();

        verifyNoInteractions(bindingService);
        org.mockito.Mockito.verifyNoMoreInteractions(deviceMapper);
    }

    @Test
    @DisplayName("零命中·BOUND 无泵类设备：监护仪类绑定保持（零解绑）")
    void handlesNoPumpDeviceWithoutUnbinding() {
        listener = new NursingInfusionCompletedListener(consumerSupport, bindingMapper, deviceMapper, bindingService);
        when(bindingMapper.selectList(any())).thenReturn(List.of(monitorBinding()));
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> listener.handleInfusionCompleted(
                        envelope("{\"executionNo\":\"%s\",\"patientId\":%d}".formatted(EXEC, PATIENT))))
                .doesNotThrowAnyException();

        verifyNoInteractions(bindingService);
    }

    @Test
    @DisplayName("不合规帧守卫：缺 executionNo/patientId ISE 死信留痕（禁静默吞）")
    void malformedPayloadsAreRejectedToDeadLetter() {
        listener = new NursingInfusionCompletedListener(consumerSupport, bindingMapper, deviceMapper, bindingService);

        assertThatThrownBy(() -> listener.handleInfusionCompleted(envelope("{\"patientId\":%d}".formatted(PATIENT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executionNo");
        assertThatThrownBy(() -> listener.handleInfusionCompleted(envelope("{\"executionNo\":\"%s\"}".formatted(EXEC))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("patientId");

        verifyNoInteractions(bindingMapper);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：消费入口落位 SYSTEM、finally 清理防串号")
    void consumptionBridgeSetsAndClearsSystemOperator() {
        listener = new NursingInfusionCompletedListener(consumerSupport, bindingMapper, deviceMapper, bindingService);
        AtomicReference<String> operatorInHandler = new AtomicReference<>();
        doAnswer(invocation -> {
                    operatorInHandler.set(OperatorContextHolder.get());
                    return null;
                })
                .when(consumerSupport)
                .consume(any(), any());

        listener.onInfusionCompleted(message);

        assertThat(operatorInHandler.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
        verify(consumerSupport).consume(any(), any());
    }

    @Test
    @DisplayName("队列绑定锚：监听器绑定 q.iot.nursing.infusion.completed（治理推导名一致）")
    void listenerBindsToGovernedQueueName() throws NoSuchMethodException {
        org.springframework.amqp.rabbit.annotation.RabbitListener binding = NursingInfusionCompletedListener.class
                .getMethod("onInfusionCompleted", Message.class)
                .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertThat(binding).as("消费入口必须挂 @RabbitListener").isNotNull();
        assertThat(binding.queues()).containsExactly(QUEUE_NAME);
    }

    // ===================== 测试数据与断言辅助 =====================

    /** BOUND 泵类绑定替身。 */
    private IotBindingEntity pumpBinding() {
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId("pump-001");
        binding.setPatientId(PATIENT);
        binding.setVisitId("I2026100200001");
        binding.setBindType(BindType.MOBILE);
        binding.setStatus(BindingStatus.BOUND);
        return binding;
    }

    /** BOUND 非泵类绑定替身（监护仪）。 */
    private IotBindingEntity monitorBinding() {
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId("monitor-001");
        binding.setPatientId(PATIENT);
        binding.setVisitId("I2026100200001");
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

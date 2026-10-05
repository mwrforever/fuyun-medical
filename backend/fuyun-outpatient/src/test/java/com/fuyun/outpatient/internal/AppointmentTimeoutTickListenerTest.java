package com.fuyun.outpatient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.outpatient.properties.OutpatientProperties;
import com.fuyun.outpatient.service.IAppointmentService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;

/**
 * 号源超时 tick 监听器单测（P2 PR-4E Task 8，W-27 tick 双通道兜底面，nursing
 * TaskOverdueTickListenerTest 克隆基准）：tick 接线（委托惰性扫描 → 扫描完成后自续期发布）、
 * tickSelfRearm 关闭跳过、GC15 SYSTEM 桥接落位/finally 清理、扫描异常上抛不续期（容器有界
 * 重试承载）。
 */
@ExtendWith(MockitoExtension.class)
class AppointmentTimeoutTickListenerTest {

    @Mock
    private IAppointmentService appointmentService;

    @Mock
    private AppointmentTimeoutTickSender tickSender;

    @Mock
    private Message message;

    @AfterEach
    void clearOperator() {
        // 防御性清理：SYSTEM 桥接残留防线程复用串号
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("tick 接线：委托惰性扫描→扫描完成后自续期发布（时序锚）")
    void tickScansAndRearmsInOrder() {
        AppointmentTimeoutTickListener listener =
                new AppointmentTimeoutTickListener(appointmentService, tickSender, selfRearm(true));
        when(appointmentService.scanAndReleaseTimedOut()).thenReturn(3);

        listener.onAppointmentTimeoutTick(message);

        // 自续期时序锚：扫描（逐单独立事务承载，调用返回即各事务均已提交）后才发布下一条 tick 心跳帧
        InOrder order = inOrder(appointmentService, tickSender);
        order.verify(appointmentService).scanAndReleaseTimedOut();
        order.verify(tickSender).sendTick();
    }

    @Test
    @DisplayName("空扫描零命中也自续期：进程存活即有心跳（零副作用断言）")
    void emptyScanStillRearmsHeartbeat() {
        AppointmentTimeoutTickListener listener =
                new AppointmentTimeoutTickListener(appointmentService, tickSender, selfRearm(true));
        when(appointmentService.scanAndReleaseTimedOut()).thenReturn(0);

        listener.onAppointmentTimeoutTick(message);

        // 心跳语义：扫描零命中（返回 0）仍发布下一条 tick——自续期与命中数解耦
        verify(tickSender).sendTick();
    }

    @Test
    @DisplayName("tickSelfRearm=false：扫描照常执行、自续期跳过（配置开关断电面）")
    void disabledSelfRearmSkipsTickPublication() {
        AppointmentTimeoutTickListener listener =
                new AppointmentTimeoutTickListener(appointmentService, tickSender, selfRearm(false));
        when(appointmentService.scanAndReleaseTimedOut()).thenReturn(0);

        listener.onAppointmentTimeoutTick(message);

        verify(appointmentService).scanAndReleaseTimedOut();
        verifyNoInteractions(tickSender);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：tick 入口落位 SYSTEM、finally 清理防串号（扫描段全程在位）")
    void tickBridgesSystemOperatorAndClears() {
        AppointmentTimeoutTickListener listener =
                new AppointmentTimeoutTickListener(appointmentService, tickSender, selfRearm(true));
        // 扫描语句触达时回读操作者上下文——断言 SYSTEM 全程在位
        AtomicReference<String> operatorInScan = new AtomicReference<>();
        when(appointmentService.scanAndReleaseTimedOut()).thenAnswer(invocation -> {
            operatorInScan.set(OperatorContextHolder.get());
            return 0;
        });

        listener.onAppointmentTimeoutTick(message);

        assertThat(operatorInScan.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("扫描段异常上抛：不自续期（容器有界重试承载——tick 帧重投幂等收敛）、SYSTEM 桥 finally 清理")
    void scanFailurePropagatesWithoutRearm() {
        AppointmentTimeoutTickListener listener =
                new AppointmentTimeoutTickListener(appointmentService, tickSender, selfRearm(true));
        when(appointmentService.scanAndReleaseTimedOut()).thenThrow(new IllegalStateException("扫描异常模拟"));

        assertThatThrownBy(() -> listener.onAppointmentTimeoutTick(message)).isInstanceOf(IllegalStateException.class);

        // 异常路径零下游动作：自续期不触达（重投由容器有界重试承载）；操作者上下文已清理
        verifyNoInteractions(tickSender);
        assertThat(OperatorContextHolder.get()).isNull();
        verify(appointmentService).scanAndReleaseTimedOut();
    }

    /**
     * 自续期开关参数替身（其余五键沿用缺省值，与 AppointmentServiceImplTest setUp 同源口径）。
     *
     * @param selfRearm 自续期开关
     * @return 门诊域参数
     */
    private static OutpatientProperties selfRearm(boolean selfRearm) {
        return new OutpatientProperties(Duration.ofMinutes(15), 1, 90, 3, 90, selfRearm);
    }
}

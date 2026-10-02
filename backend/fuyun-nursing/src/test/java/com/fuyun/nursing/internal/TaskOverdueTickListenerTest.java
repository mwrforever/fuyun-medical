package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.service.ITaskOverdueService;
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
 * 任务逾期 tick 监听器单测（P2 PR-3 Task 9，GC10 新增 internal 类覆盖义务）：tick 三段式接线
 * （扫表升级段①② → 回签补偿扫描挂接 → 自续期发布段③）、tickSelfRearm 关闭跳过、GC15 SYSTEM
 * 桥接落位/finally 清理、段③时序锚（扫描与补偿事务提交后才自续期）与异常上抛不续期。
 */
@ExtendWith(MockitoExtension.class)
class TaskOverdueTickListenerTest {

    @Mock
    private ITaskOverdueService taskOverdueService;

    @Mock
    private ExecutionConfirmCompensator compensator;

    @Mock
    private TaskOverdueTickSender tickSender;

    @Mock
    private Message message;

    @AfterEach
    void clearOperator() {
        // 防御性清理：SYSTEM 桥接残留防线程复用串号
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("tick 三段式接线：扫表升级→补偿扫描→事务提交后自续期发布（时序锚）")
    void tickScansEscalatesCompensatesAndRearmsInOrder() {
        TaskOverdueTickListener listener =
                new TaskOverdueTickListener(taskOverdueService, compensator, tickSender, selfRearm(true));
        when(taskOverdueService.scanAndEscalate()).thenReturn(3);
        when(compensator.compensate()).thenReturn(1);

        listener.onTaskOverdueTick(message);

        // 段③自续期发布断言：扫描与补偿（各自独立事务承载）完成后发布下一条 tick 心跳帧
        InOrder order = inOrder(taskOverdueService, compensator, tickSender);
        order.verify(taskOverdueService).scanAndEscalate();
        order.verify(compensator).compensate();
        order.verify(tickSender).sendTick();
    }

    @Test
    @DisplayName("空扫描零命中也自续期：进程存活即有心跳（零副作用断言）")
    void emptyScanStillRearmsHeartbeat() {
        TaskOverdueTickListener listener =
                new TaskOverdueTickListener(taskOverdueService, compensator, tickSender, selfRearm(true));
        when(taskOverdueService.scanAndEscalate()).thenReturn(0);
        when(compensator.compensate()).thenReturn(0);

        listener.onTaskOverdueTick(message);

        // 心跳语义：扫描零命中（返回 0）仍发布下一条 tick——自续期与命中数解耦
        verify(tickSender).sendTick();
    }

    @Test
    @DisplayName("tickSelfRearm=false：扫描与补偿照常执行、自续期跳过（配置开关断电面）")
    void disabledSelfRearmSkipsTickPublication() {
        TaskOverdueTickListener listener =
                new TaskOverdueTickListener(taskOverdueService, compensator, tickSender, selfRearm(false));
        when(taskOverdueService.scanAndEscalate()).thenReturn(0);
        when(compensator.compensate()).thenReturn(0);

        listener.onTaskOverdueTick(message);

        verify(taskOverdueService).scanAndEscalate();
        verify(compensator).compensate();
        verifyNoInteractions(tickSender);
    }

    @Test
    @DisplayName("GC15 SYSTEM 桥接：tick 入口落位 SYSTEM、finally 清理防串号（含补偿挂接段）")
    void tickBridgesSystemOperatorAndClears() {
        TaskOverdueTickListener listener =
                new TaskOverdueTickListener(taskOverdueService, compensator, tickSender, selfRearm(true));
        // 扫描与补偿语句触达时回读操作者上下文——断言 SYSTEM 全程在位（含 Compensator 挂接调用段）
        AtomicReference<String> operatorInScan = new AtomicReference<>();
        AtomicReference<String> operatorInCompensate = new AtomicReference<>();
        when(taskOverdueService.scanAndEscalate()).thenAnswer(invocation -> {
            operatorInScan.set(OperatorContextHolder.get());
            return 0;
        });
        when(compensator.compensate()).thenAnswer(invocation -> {
            operatorInCompensate.set(OperatorContextHolder.get());
            return 0;
        });

        listener.onTaskOverdueTick(message);

        assertThat(operatorInScan.get()).isEqualTo("SYSTEM");
        assertThat(operatorInCompensate.get()).isEqualTo("SYSTEM");
        assertThat(OperatorContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("扫描段异常上抛：不补偿不自续期（容器重试承载）、SYSTEM 桥 finally 清理")
    void scanFailurePropagatesWithoutCompensateOrRearm() {
        TaskOverdueTickListener listener =
                new TaskOverdueTickListener(taskOverdueService, compensator, tickSender, selfRearm(true));
        when(taskOverdueService.scanAndEscalate()).thenThrow(new IllegalStateException("扫描异常模拟"));

        assertThatThrownBy(() -> listener.onTaskOverdueTick(message)).isInstanceOf(IllegalStateException.class);

        // 异常路径零下游动作：补偿与自续期不触达（重投由容器有界重试承载）；操作者上下文已清理
        verifyNoInteractions(compensator, tickSender);
        assertThat(OperatorContextHolder.get()).isNull();
        verify(taskOverdueService).scanAndEscalate();
    }

    /**
     * 自续期开关参数组替身（其余三键沿用缺省值）。
     *
     * @param selfRearm 自续期开关
     * @return 护理域参数
     */
    private static NursingProperties selfRearm(boolean selfRearm) {
        return new NursingProperties(30, new NursingProperties.TaskOverdue(30, 60, 30, selfRearm));
    }
}

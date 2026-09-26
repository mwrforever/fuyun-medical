package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.CommandResultFrame;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 命令结果帧监听器单测（P2 PR-2 Task 8）：受理留痕后移交编排器（completeFromResultFrame 为
 * 终态迁移与事件发布单点），处理失败原样上抛交消费者业务失败路径（会话重建令帧回归重投域）。
 */
class IotDeviceCommandListenerTest {

    private CommandDispatcher dispatcher;

    private IotDeviceCommandListener listener;

    @BeforeEach
    void setUp() {
        dispatcher = mock(CommandDispatcher.class);
        listener = new IotDeviceCommandListener(dispatcher);
    }

    @Test
    @DisplayName("结果帧受理：原样移交编排器 completeFromResultFrame（终态迁移与事件发布单点）")
    void onCommandResultFrameDelegatesToDispatcher() {
        CommandResultFrame frame = new CommandResultFrame(
                "dev-cmd-01", "sim-cmd-2", "SUCCESS", null, Instant.parse("2026-09-26T01:02:03Z"));

        listener.onCommandResultFrame(frame);

        ArgumentCaptor<CommandResultFrame> captor = ArgumentCaptor.forClass(CommandResultFrame.class);
        verify(dispatcher).completeFromResultFrame(captor.capture());
        assertThat(captor.getValue()).isSameAs(frame);
    }

    @Test
    @DisplayName("编排器处理失败：异常原样上抛（交消费者业务失败路径，不吞错改语义）")
    void dispatcherFailurePropagates() {
        CommandResultFrame frame = new CommandResultFrame(
                "dev-cmd-01", "sim-cmd-2", "FAILED", "执行失败", Instant.parse("2026-09-26T01:02:03Z"));
        doThrow(new IllegalStateException("终态迁移失败")).when(dispatcher).completeFromResultFrame(frame);

        assertThatThrownBy(() -> listener.onCommandResultFrame(frame))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态迁移失败");
    }
}

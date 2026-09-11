package com.fuyun.iotsimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fuyun.iotsimulator.config.SimulatorConfig;
import com.fuyun.iotsimulator.mqtt.IotdaMqttClient;
import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 模拟设备主程序单测（BRIEF-PR4-01 §5）：上行周期体（成功发布与单帧失败不中断循环）、
 * 周期任务装配（1 秒档真实触发上行）——MqttClient 以 Mockito 桩替，零真实网络。
 */
@ExtendWith(MockitoExtension.class)
class IotSimulatorApplicationTest {

    /** 测试专用最小配置：1 秒上行周期压短装配验证时长 */
    private static final SimulatorConfig ONE_SECOND_CONFIG =
            new SimulatorConfig("tcp://127.0.0.1:1883", "dev-001", "secret-001", 1);

    @Mock
    private IotdaMqttClient mqttClient;

    private ScheduledExecutorService startedScheduler;

    @AfterEach
    void tearDown() {
        if (startedScheduler != null) {
            startedScheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("上行周期体：单帧失败（如断链）被吞并记录，不中断调度循环（scheduleWithFixedDelay 抛错即停的防线）")
    void reportCycleSwallowsPublishFailure() throws MqttException {
        doThrow(new MqttException(new RuntimeException("connection lost")))
                .when(mqttClient)
                .publish(anyString());

        assertThatCode(() -> IotSimulatorApplication.runReportCycle(mqttClient, new TelemetryPayloadBuilder(42L)))
                .as("单帧上行失败不得向外抛（否则固定延迟调度被取消，上行静默终止）")
                .doesNotThrowAnyException();
        verify(mqttClient).publish(anyString());
    }

    @Test
    @DisplayName("周期装配：1 秒档 scheduleWithFixedDelay 真实触发上行发布")
    void launchSchedulesPeriodicReporting() throws MqttException {
        startedScheduler = IotSimulatorApplication.launchReporting(ONE_SECOND_CONFIG, mqttClient);

        assertThat(startedScheduler.isShutdown()).as("装配返回的调度器处于运行态").isFalse();
        // 第二帧到达即证明周期性调度（scheduleWithFixedDelay 按配置档重复触发，而非单次）
        verify(mqttClient, timeout(5000).atLeastOnce()).publish(anyString());
        verify(mqttClient, timeout(5000).times(2)).publish(anyString());
    }

    @Test
    @DisplayName("优雅停机：取消周期任务、停调度器、断连接按序收口（shutdownHook 复用同一收口动作）")
    void stopReportingCancelsTaskShutsDownSchedulerAndClosesClient() {
        startedScheduler = IotSimulatorApplication.launchReporting(ONE_SECOND_CONFIG, mqttClient);

        IotSimulatorApplication.stopReporting(
                startedScheduler.schedule(() -> {}, 0, TimeUnit.SECONDS),
                startedScheduler,
                mqttClient,
                ONE_SECOND_CONFIG.deviceId());

        assertThat(startedScheduler.isShutdown()).as("调度器已停").isTrue();
        verify(mqttClient).close();
    }

    @Test
    @DisplayName("建链失败终止启动：broker 不可达时 launch 以中文异常终止（容器编排按失败重启处置）")
    void launchFailsFastWhenBrokerUnreachable() {
        SimulatorConfig unreachable = new SimulatorConfig(
                "tcp://127.0.0.1:1", "dev-001", "secret-001", ONE_SECOND_CONFIG.reportIntervalSeconds());

        assertThatCode(() -> IotSimulatorApplication.launch(unreachable))
                .as("建链失败必须终止进程而非静默运行")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("建链失败");
    }
}

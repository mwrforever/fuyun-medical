package com.fuyun.iot.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.internal.TelemetrySummaryAggregator;
import com.fuyun.iot.service.ITelemetryPushService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 遥测推送服务生命周期单测（P2 PR-2 Task 11，包级同包直驱 flush 入口——跨包主测试类不可见
 * 的兜底排空面在此承载）：SmartLifecycle 启停幂等与运行态、兜底排空线程体一次执行（窗口到期
 * 出帧）、排空失败吞错不打断调度。主行为测试（主题路径/载荷/节流合并）归
 * com.fuyun.iot.service.TelemetryPushServiceImplTest。
 *
 * <p>窗口边界以 SteppingClock 注入；调度器真实线程仅在启停用例启动（初始延迟 0 保证循环体
 * 至少执行一次后停机）。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryPushServiceImplLifecycleTest {

    /** 测试病区 ID：窗口路由键 */
    private static final long WARD_ID = 1001L;

    /** 窗口起点锚（UTC，测试推进基准） */
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    /** 可推进时钟：窗口边界注入载体 */
    private TelemetryPushServiceImplLifecycleTest.SteppingClock clock;

    private TelemetrySummaryAggregator aggregator;

    private TelemetryPushServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new TelemetryPushServiceImplLifecycleTest.SteppingClock(T0);
        aggregator = new TelemetrySummaryAggregator(clock);
        service = new TelemetryPushServiceImpl(messagingTemplate, aggregator);
    }

    @Test
    @DisplayName("SmartLifecycle 启停：auto-startup 声明、启动即运行态、停机即退出（兜底线程随上下文关闭）")
    void lifecycleStartStopTransitionsRunningState() throws Exception {
        assertThat(service.isAutoStartup()).isTrue();
        assertThat(service.isRunning()).isFalse();

        service.start();
        service.start();
        assertThat(service.isRunning()).as("重复启动幂等（CAS 闸门）").isTrue();
        // 初始延迟 0：循环体至少执行一次（空窗排空无副作用），留短暂窗口覆盖线程体后停机
        Thread.sleep(100);
        service.stop();
        service.stop();
        assertThat(service.isRunning()).as("重复停机幂等（CAS 闸门）").isFalse();
    }

    @Test
    @DisplayName("停机中断防御：停机等待被中断时复位中断标记并完成关闭（不吞中断语义）")
    void stopRestoresInterruptFlagWhenAwaitInterrupted() {
        service.start();
        try {
            // 预置当前线程中断标记：awaitTermination 立即抛 InterruptedException 走中断复位分支
            Thread.currentThread().interrupt();
            service.stop();
        } finally {
            // 复位测试残留中断标记（不影响断言：stop 内部已消费中断并复位一次）
            Thread.interrupted();
        }
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("兜底排空线程体：窗口到期排空并推送摘要帧（遥测停流场景尾帧按时出帧）")
    void flushDueWindowsSendsExpiredWindowFrames() {
        IotTelemetryEntity entity = telemetry("dev-001", "MDC_ECG_HEART_RATE");
        service.pushSummary(List.of(entity), WARD_ID);
        verifyNoInteractions(messagingTemplate);

        clock.advance(Duration.ofSeconds(3));
        service.flushDueWindowsQuietly();

        verify(messagingTemplate).convertAndSend(eq("/topic/iot/telemetry/" + WARD_ID), eq(expiredFramePayload()));
    }

    @Test
    @DisplayName("兜底排空失败吞错：发送异常捕获留痕不上抛（不打断调度周期）")
    void flushDueWindowsSwallowsSendFailures() {
        service.pushSummary(List.of(telemetry("dev-001", "MDC_ECG_HEART_RATE")), WARD_ID);
        clock.advance(Duration.ofSeconds(3));
        doThrow(new IllegalStateException("STOMP 通道故障"))
                .when(messagingTemplate)
                .convertAndSend(
                        eq("/topic/iot/telemetry/" + WARD_ID),
                        org.mockito.ArgumentMatchers.any(ITelemetryPushService.TelemetrySummary.class));

        service.flushDueWindowsQuietly();
    }

    /** 窗口到期帧的预期契约载荷（条数 1/上界=行时刻/单明细），与发送侧映射一一对应 */
    private ITelemetryPushService.TelemetrySummary expiredFramePayload() {
        return new ITelemetryPushService.TelemetrySummary(
                1,
                Instant.parse("2026-09-26T08:00:01Z"),
                List.of(new ITelemetryPushService.Item("dev-001", "MDC_ECG_HEART_RATE")));
    }

    /** 构造遥测实体（occurredAt = T0+1s，上界断言值源） */
    private static IotTelemetryEntity telemetry(String deviceId, String metricCode) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(deviceId);
        entity.setMetricCode(metricCode);
        entity.setOccurredAt(OffsetDateTime.ofInstant(T0.plusSeconds(1), ZoneOffset.UTC));
        entity.setQuality(TelemetryQuality.GOOD);
        return entity;
    }

    /** 可推进固定时区时钟（窗口边界注入载体） */
    private static final class SteppingClock extends Clock {

        private volatile Instant current;

        SteppingClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration step) {
            current = current.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

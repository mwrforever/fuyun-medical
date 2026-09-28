package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.TelemetryQuality;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 遥测摘要 2 秒窗口聚合器单测（P2 PR-2 Task 11 Step 3，TDD 先行）：同病区窗口内多批合并单帧
 * （条数求和/items 去重保序/上界取最大）、窗口到期边界排空（时钟注入推进）、病区间窗口独立、
 * 防御分支（空批/无病区不进窗）、排空后重锚新窗。推送线程不参与单测（SmartLifecycle 循环体
 * 即 drainExpired，直接断言）。
 */
class TelemetrySummaryAggregatorTest {

    /** 测试病区 ID（主窗口路由键） */
    private static final long WARD_ID = 1001L;

    /** 其他病区 ID（病区间窗口独立断言） */
    private static final long OTHER_WARD_ID = 2002L;

    /** 窗口起点锚（UTC，测试推进基准） */
    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    /** 可推进时钟：窗口边界注入载体（brief「时钟注入或窗口边界注入」） */
    private final SteppingClock clock = new SteppingClock(T0);

    private final TelemetrySummaryAggregator aggregator = new TelemetrySummaryAggregator(clock);

    @Test
    @DisplayName("同病区窗口内多批合并：窗口未到期不产帧，到期后排空产出单帧（条数求和/items 去重保序/上界取最大）")
    void mergesMultipleBatchesWithinWindowIntoSingleFrame() {
        // 第一批：dev-1 心率 + dev-2 心率（窗口开启锚 = T0，行时刻 T0+1s/T0+2s）
        assertThat(aggregator.offer(batchAt(0, "dev-1", "MDC_ECG_HEART_RATE", "dev-2", "MDC_ECG_HEART_RATE"), WARD_ID))
                .as("窗口首触不产帧（攒批中）")
                .isEmpty();
        // 第二批（窗口内 +1s 到批，行时刻 T0+4s/T0+5s）：dev-1 心率重复 + dev-1 血氧（新指标）
        clock.advance(Duration.ofSeconds(1));
        assertThat(aggregator.offer(batchAt(3, "dev-1", "MDC_ECG_HEART_RATE", "dev-1", "MDC_SPO2"), WARD_ID))
                .as("窗口未到期合并批不产帧（节流语义）")
                .isEmpty();
        // 推进越过窗口边界（累计 +3s）：下批触发达期排空——两批合并为单帧
        clock.advance(Duration.ofSeconds(2));
        List<TelemetrySummaryAggregator.WindowFrame> frames =
                aggregator.offer(batchAt(6, "dev-3", "MDC_ECG_HEART_RATE"), WARD_ID);

        assertThat(frames).hasSize(1);
        TelemetrySummaryAggregator.WindowFrame frame = frames.get(0);
        assertThat(frame.wardId()).isEqualTo(WARD_ID);
        assertThat(frame.count()).as("条数 = 两批真实条数之和（4），不随 items 去重减少").isEqualTo(4);
        assertThat(frame.items())
                .extracting(
                        TelemetrySummaryAggregator.WindowItem::deviceId,
                        TelemetrySummaryAggregator.WindowItem::metricCode)
                .containsExactly(
                        Tuple.tuple("dev-1", "MDC_ECG_HEART_RATE"),
                        Tuple.tuple("dev-2", "MDC_ECG_HEART_RATE"),
                        Tuple.tuple("dev-1", "MDC_SPO2"));
        assertThat(frame.occurredAtUpperBound()).as("上界取窗口内最大发生时刻（第二批末行）").isEqualTo(T0.plusSeconds(5));
    }

    @Test
    @DisplayName("窗口到期边界排空：drainExpired 独立排空到期窗口（时钟推进驱动，不依赖后续到批）")
    void drainExpiredFlushesDueWindowsWithoutNewTraffic() {
        aggregator.offer(batchAt(0, "dev-1", "MDC_ECG_HEART_RATE"), WARD_ID);
        assertThat(aggregator.drainExpired()).as("窗口未到期排空为空").isEmpty();

        clock.advance(Duration.ofSeconds(3));
        List<TelemetrySummaryAggregator.WindowFrame> frames = aggregator.drainExpired();

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).wardId()).isEqualTo(WARD_ID);
        assertThat(frames.get(0).count()).isEqualTo(1);
        // 已排空窗口不重复产帧（幂等）
        assertThat(aggregator.drainExpired()).as("重复排空不重复产帧").isEmpty();
    }

    @Test
    @DisplayName("病区间窗口独立：两病区各自开窗，到期后各自产帧互不合并")
    void keepsWindowsIndependentAcrossWards() {
        aggregator.offer(batchAt(0, "dev-1", "MDC_ECG_HEART_RATE"), WARD_ID);
        aggregator.offer(batchAt(0, "dev-9", "MDC_SPO2"), OTHER_WARD_ID);

        clock.advance(Duration.ofSeconds(3));
        List<TelemetrySummaryAggregator.WindowFrame> frames = aggregator.drainExpired();

        assertThat(frames).hasSize(2);
        assertThat(frames)
                .extracting(TelemetrySummaryAggregator.WindowFrame::wardId)
                .containsExactlyInAnyOrder(WARD_ID, OTHER_WARD_ID);
        assertThat(frames.stream()
                        .filter(f -> f.wardId() == WARD_ID)
                        .findFirst()
                        .orElseThrow()
                        .items())
                .as("病区间 items 不串窗")
                .allSatisfy(item -> assertThat(item.deviceId()).isEqualTo("dev-1"));
    }

    @Test
    @DisplayName("防御分支：空批次与无病区归属不进窗（推送主链已在服务层跳过，聚合器同口径兜底）")
    void ignoresEmptyBatchAndNullWard() {
        assertThat(aggregator.offer(List.of(), WARD_ID)).isEmpty();
        assertThat(aggregator.offer(batchAt(0, "dev-1", "MDC_ECG_HEART_RATE"), null))
                .isEmpty();
        clock.advance(Duration.ofSeconds(3));
        assertThat(aggregator.drainExpired()).as("防御批未进窗，无帧可排").isEmpty();
    }

    @Test
    @DisplayName("排空后重锚新窗：下一批从新窗口起点独立累计，不残留上窗条数与明细")
    void reanchorsNewWindowAfterFlush() {
        aggregator.offer(batchAt(0, "dev-1", "MDC_ECG_HEART_RATE"), WARD_ID);
        clock.advance(Duration.ofSeconds(3));
        assertThat(aggregator.drainExpired()).hasSize(1);

        // 新窗首触：仅本批条数（上窗已排空，不残留）
        List<TelemetrySummaryAggregator.WindowFrame> frames =
                aggregator.offer(batchAt(0, "dev-5", "MDC_SPO2", "dev-6", "MDC_SPO2"), WARD_ID);
        assertThat(frames).as("新窗首触不产帧").isEmpty();

        clock.advance(Duration.ofSeconds(3));
        List<TelemetrySummaryAggregator.WindowFrame> drained = aggregator.drainExpired();
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).count()).as("新窗条数仅含新批").isEqualTo(2);
    }

    /** 构造一批遥测实体（deviceId/metricCode 交替成对；行时刻自 baseSeq 起 1s 递增区分上界） */
    private static List<IotTelemetryEntity> batchAt(int baseSeq, String... deviceMetricPairs) {
        List<IotTelemetryEntity> written = new ArrayList<>(deviceMetricPairs.length / 2);
        for (int i = 0; i < deviceMetricPairs.length; i += 2) {
            IotTelemetryEntity entity = new IotTelemetryEntity();
            entity.setDeviceId(deviceMetricPairs[i]);
            entity.setMetricCode(deviceMetricPairs[i + 1]);
            entity.setOccurredAt(OffsetDateTime.ofInstant(T0.plusSeconds(baseSeq + (long) i / 2 + 1), ZoneOffset.UTC));
            entity.setQuality(TelemetryQuality.GOOD);
            written.add(entity);
        }
        return written;
    }

    /** 可推进固定时区时钟（单测窗口边界注入载体；advance 显式推时针，生产装配为 Clock.systemUTC()） */
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

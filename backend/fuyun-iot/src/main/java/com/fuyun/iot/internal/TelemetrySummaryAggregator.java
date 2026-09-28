package com.fuyun.iot.internal;

import com.fuyun.iot.entity.IotTelemetryEntity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 遥测摘要 2 秒窗口按床位合并节流聚合器（FU-M14-07 推送完整化，P2 PR-2 Task 11）：同病区
 * （床位经绑定归属病区，路由维度即病区主题）窗口内多批合并单帧推送——同一窗口内重复出现的
 * 设备×指标明细去重保序，条数保留真实累计值，occurredAt 上界取窗口内最大发生时刻。
 *
 * <p><b>窗口语义（纯状态机，无内建线程）</b>：每病区独立窗口，首触开窗（锚 = 时钟当下），
 * 2 秒内的后续批次合入当前窗，窗口到期（now ≥ 锚+2s）后排空产帧——排空两路触发：①推送服务
 * 的 flush 线程按固定周期调 {@link #drainExpired()}（尾帧兜底，无后续到批也能按时出帧）；
 * ②每次 {@link #offer} 先排空已到期窗口再合入本批（高流量路径即时出帧，不等轮询周期）。
 * 已排空窗口不重复产帧（幂等）。
 *
 * <p><b>可测性（brief「时钟注入或窗口边界注入」）</b>：时钟经构造器注入（单测 SteppingClock
 * 推进窗口边界；生产装配 Clock.systemUTC()，由 IotWebSocketConfig @Bean 显式构造（BillingWebConfig
 * 取价时钟同款先例）——不注册全局 Clock Bean：原 iotPushClock Bean 与 IotAmqpConfig 条件装配
 * iotAmqpClock 并存时，Spring Modulith 事件注册表按类型无标识解析 Clock（ObjectProvider 注入，
 * 挂不上 @Qualifier）即二义失败（Task 18 探针 D1，2026-09-27 修复）。
 *
 * <p>线程安全：offer/drainExpired 同一把内部锁串行化（窗口状态全在这两入口变更），推送失败
 * 由调用方处置，本类不感知 STOMP 基础设施。归 internal/ 包（模块内机制件，宪法 B.1）；装配归
 * IotWebSocketConfig @Bean 显式构造。
 */
@Slf4j
public class TelemetrySummaryAggregator {

    /** 窗口宽度：2 秒（brief 冻结节流窗口；到期边界含端点——now ≥ 锚+2s 即到期） */
    public static final Duration WINDOW = Duration.ofSeconds(2);

    /** 时钟源：窗口边界判定唯一时间来源（注入可测） */
    private final Clock clock;

    /** 病区 → 窗口缓冲（this 锁串行化访问；排空即移除槽位，活跃病区量级 = 病区数，可控） */
    private final Map<Long, WindowBuffer> buffers = new HashMap<>();

    /**
     * 全参构造器（装配归 IotWebSocketConfig @Bean 显式构造，backend 宪法 B.1——时钟不注册全局
     * Bean，Modulith 按类型解析面只留 iotAmqpClock 单候选，D1 修复形态）。
     *
     * @param clock 窗口边界时钟，非空；来源：IotWebSocketConfig 装配点（生产 Clock.systemUTC()）
     *              或单测固定/可推进时钟
     */
    public TelemetrySummaryAggregator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 归并一批遥测实体进病区窗口：先排空全部已到期窗口（产帧），再将本批合入当前窗（窗口内
     * 合并节流；到期则先出旧帧再开新窗锚）。空批/无病区归属防御性不进窗（推送服务主链已按
     * 同口径跳过，本层兜底保证状态纯净）。
     *
     * @param written 本批已写入遥测实体，允许为空（防御跳过）；来源：TelemetryIngestServiceImpl
     *                落库成功后按病区分组透传
     * @param wardId  病区 ID（窗口路由键），允许为 null（无绑定快照批次，进不了窗）；来源：
     *                绑定快照 ward_id
     * @return 本轮到期排空的窗口帧清单（含触发射出的旧窗），非空；无到期窗口为空清单
     */
    public synchronized List<WindowFrame> offer(List<IotTelemetryEntity> written, Long wardId) {
        List<WindowFrame> flushed = drainExpired();
        if (wardId == null || written == null || written.isEmpty()) {
            return flushed;
        }
        Instant now = clock.instant();
        WindowBuffer buffer = buffers.computeIfAbsent(wardId, key -> new WindowBuffer());
        if (buffer.count > 0 && !now.isBefore(buffer.windowStart.plus(WINDOW))) {
            // 当前窗已到期：先出帧再开新窗锚（旧窗数据不与本批混淆）
            flushed.add(buffer.toFrame(wardId));
            buffer.reset();
            buffer.windowStart = now;
        } else if (buffer.count == 0) {
            // 新窗首触：开窗锚（窗口起点 = 首批到批时刻）
            buffer.windowStart = now;
        }
        buffer.merge(written);
        return flushed;
    }

    /**
     * 排空全部到期窗口（now ≥ 窗口锚+2s）：推送服务 flush 线程固定周期调起（尾帧兜底）与
     * offer 前置排空共用同一入口（同一把锁，窗口帧恰产一次，双路触发不重复）。
     *
     * @return 到期窗口帧清单（帧间无序——病区间相互独立），非空；无到期窗口为空清单
     */
    public synchronized List<WindowFrame> drainExpired() {
        List<WindowFrame> frames = new ArrayList<>();
        Instant now = clock.instant();
        Iterator<Map.Entry<Long, WindowBuffer>> iterator = buffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, WindowBuffer> entry = iterator.next();
            WindowBuffer buffer = entry.getValue();
            if (buffer.count > 0 && !now.isBefore(buffer.windowStart.plus(WINDOW))) {
                frames.add(buffer.toFrame(entry.getKey()));
                // 排空即移除槽位：下批重锚新窗，避免长期残留空缓冲
                iterator.remove();
            }
        }
        if (!frames.isEmpty()) {
            log.info(
                    "遥测摘要窗口到期排空：frames={}（病区={})",
                    frames.size(),
                    frames.stream().map(WindowFrame::wardId).toList());
        }
        return frames;
    }

    /**
     * 窗口缓冲（单病区当前窗聚合态）：this 锁内访问，无独立同步。
     */
    private static final class WindowBuffer {

        /** 窗口起点锚（首触时刻；重锚于到期出帧后） */
        private Instant windowStart;

        /** 窗口内真实条数累计（不随 items 去重减少——订阅方以 count 为准的契约延续） */
        private long count;

        /** 窗口内最大发生时刻（摘要时间锚，可空——防御性容 null 行） */
        private Instant upperBound;

        /** 窗口内明细（deviceId:metricCode → 明细，插入序保序 + 去重） */
        private final LinkedHashMap<String, WindowItem> items = new LinkedHashMap<>();

        /** 归并一批实体：条数累计、上界取最大、明细去重合入（首见保序）。 */
        void merge(List<IotTelemetryEntity> written) {
            for (IotTelemetryEntity entity : written) {
                count++;
                if (entity.getOccurredAt() != null) {
                    Instant occurred = entity.getOccurredAt().toInstant();
                    if (upperBound == null || occurred.isAfter(upperBound)) {
                        upperBound = occurred;
                    }
                }
                items.putIfAbsent(
                        entity.getDeviceId() + ":" + entity.getMetricCode(),
                        new WindowItem(entity.getDeviceId(), entity.getMetricCode()));
            }
        }

        /** 当前窗出帧（快照后由调用方 reset）。 */
        WindowFrame toFrame(Long wardId) {
            return new WindowFrame(wardId, count, upperBound, List.copyOf(items.values()));
        }

        /** 窗口归零（出帧后重锚前调用）。 */
        void reset() {
            count = 0;
            upperBound = null;
            items.clear();
        }
    }

    /**
     * 窗口帧（排空产物，推送服务据此前推摘要主题）：wardId 为主题路由键，其余三字段经推送
     * 服务映射为 TelemetrySummary 契约载荷（items 截上限在推送侧承载，窗口帧保持全量语义）。
     *
     * @param wardId              病区 ID，非空
     * @param count               窗口内真实条数累计，非空
     * @param occurredAtUpperBound 窗口内最大发生时刻（UTC），可空（窗口内全部行缺时刻的防御场景）
     * @param items               明细清单（deviceId+metricCode，窗口内去重保序），非空
     */
    public record WindowFrame(Long wardId, long count, Instant occurredAtUpperBound, List<WindowItem> items) {}

    /**
     * 窗口明细二元组（与推送契约 Item 同构，独立声明保持聚合器零服务层类型依赖）。
     *
     * @param deviceId   IoTDA 设备标识，非空
     * @param metricCode 指标编码，非空
     */
    public record WindowItem(String deviceId, String metricCode) {}
}

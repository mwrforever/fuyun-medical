package com.fuyun.iot.service.impl;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.internal.TelemetrySummaryAggregator;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.DashboardSummaryVO;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 遥测 STOMP 推送服务实现（四主题完整化，BRIEF-PR4-01 §4 + P2 PR-2 Task 11）：经
 * {@link SimpMessagingTemplate} 向内存 SimpleBroker（/topic 前缀）分发，进程内直达已订阅
 * WebSocket 会话。四主题：遥测摘要（2s 窗口节流）/设备状态/告警/全院运营摘要。
 *
 * <p><b>遥测摘要 2s 窗口节流（FU-M14-07 完整化）</b>：pushSummary 不再逐批直推——批次经
 * {@link TelemetrySummaryAggregator} 归并进病区 2s 窗口，窗口到期由两路排空推送（offer 前置
 * 排空 + 本类 flush 线程固定周期兜底排空，同锁恰产一次）；items 明细在发送侧截至
 * {@link #SUMMARY_MAX_ITEMS}（条数保留窗口内真实累计值）。尾帧兜底线程沿用攒批器
 * TelemetryBatchAssembler 的 SmartLifecycle 单线程先例（非 @Scheduled——宪法 A.5-14 的
 * ShedLock 互斥针对共享状态定时任务，本线程仅排空进程内窗口，跨实例加锁反而阻止其余实例
 * 各自推送其会话；线程有界命名随上下文关闭，宪法 B.3-4）。停机丢尾帧口径：摘要帧为易逝展示
 * 数据（遥测行已落库，REST 兜底可查），停机不做补推。
 *
 * <p>调用方须遵守宪法 A.4.2-7"事务内禁止远程调用、消息发送与人工等待，对外调用在事务提交后
 * 执行"——本类不感知事务，由调用侧保证时序：入库侧 TelemetryIngestServiceImpl 以 afterCommit
 * 回调承接，消费监听器侧运行于 MQ 消费线程（无事务上下文）。推送失败原样向调用方抛出，由调用
 * 方按各自语义处置（入库侧吞并告警、监听器侧业务失败重抛）。
 *
 * <p>装配归 fuyun-app IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1）。
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryPushServiceImpl implements ITelemetryPushService, SmartLifecycle {

    /** 尾帧兜底排空轮询周期（毫秒）：窗口 2s 内恰四轮轮询（2000/500），尾帧出帧延迟 ≤ 窗口+周期 */
    private static final long FLUSH_POLL_MILLIS = 500L;

    /** STOMP 发送模板：SimpleBroker 通道唯一发送口（来源：@EnableWebSocketMessageBroker 基础设施） */
    private final SimpMessagingTemplate messagingTemplate;

    /** 遥测摘要窗口聚合器：2s 按病区合并节流的状态机（时钟注入可测） */
    private final TelemetrySummaryAggregator summaryAggregator;

    /** 生命周期运行标记：start/stop CAS 闸门（SmartLifecycle 幂等重入防御） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 单线程兜底排空调度器（命名、单线程、随上下文关闭，宪法 B.3-4 线程池纪律） */
    private volatile ScheduledExecutorService flushExecutor;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param messagingTemplate STOMP 发送模板，非空；来源：IotWebSocketConfig 消息代理基础设施
     * @param summaryAggregator 遥测摘要窗口聚合器，非空；来源：IotConfig 装配链（时钟注入载体）
     */
    public TelemetryPushServiceImpl(
            SimpMessagingTemplate messagingTemplate, TelemetrySummaryAggregator summaryAggregator) {
        this.messagingTemplate = messagingTemplate;
        this.summaryAggregator = summaryAggregator;
    }

    @Override
    public void pushSummary(List<IotTelemetryEntity> written, Long wardId) {
        // 跳过分支：无绑定快照（wardId null）或空批次不进窗（简报 §1.4"无绑定快照的帧仅落库"）
        if (wardId == null || written == null || written.isEmpty()) {
            log.info("遥测摘要推送跳过（无病区归属或空批次）：wardId={}，count={}", wardId, written == null ? 0 : written.size());
            return;
        }
        // 2s 窗口节流：批次归并进病区窗口，触发排空的到期窗口逐帧推送（窗口内多批合并单帧）
        for (TelemetrySummaryAggregator.WindowFrame frame : summaryAggregator.offer(written, wardId)) {
            sendSummaryFrame(frame);
        }
    }

    @Override
    public void pushDeviceStatus(DeviceStatusEvent event) {
        // 跳过分支：载荷 wardId 为空（P0 状态帧契约不含 wardId）info 跳过推送（简报 §1.5 口径）
        if (event.wardId() == null) {
            log.info("设备状态帧推送跳过（载荷无病区归属）：deviceId={}，status={}", event.deviceId(), event.status());
            return;
        }
        // 载荷 = 事件契约 record 本体（deviceId/status/occurredAt/wardId 四字段同构，简报 §1.5）
        messagingTemplate.convertAndSend(IotMessagingConstants.TOPIC_DEVICE_STATUS_PREFIX + event.wardId(), event);
        log.info(
                "设备状态帧已推送：topic={}{}，deviceId={}，status={}，occurredAt={}",
                IotMessagingConstants.TOPIC_DEVICE_STATUS_PREFIX,
                event.wardId(),
                event.deviceId(),
                event.status(),
                event.occurredAt());
    }

    @Override
    public void pushAlarm(IotAlarmEntity alarm) {
        // 跳过分支：wardId 为空属引擎侧防御缺口（病区路由缺失的告警在引擎已跳过新发），info 留痕
        if (alarm.getWardId() == null) {
            log.info("告警帧推送跳过（告警行无病区归属）：alarmNo={}", alarm.getAlarmNo());
            return;
        }
        messagingTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_ALARM_PREFIX + alarm.getWardId(), buildAlarmPayload(alarm));
        log.info(
                "告警帧已推送：topic={}{}，alarmNo={}，level={}，deviceId={}",
                IotMessagingConstants.TOPIC_ALARM_PREFIX,
                alarm.getWardId(),
                alarm.getAlarmNo(),
                alarm.getAlarmLevel(),
                alarm.getDeviceId());
    }

    @Override
    public void pushLinkageNotify(IotAlarmEntity alarm, String linkageNo) {
        // 跳过分支：wardId 为空无法定推告警主题（与 pushAlarm 同口径 info 留痕，联动侧按回执裁决）
        if (alarm.getWardId() == null) {
            log.info("联动强提醒推送跳过（告警行无病区归属）：alarmNo={}，linkageNo={}", alarm.getAlarmNo(), linkageNo);
            return;
        }
        // 载荷与告警帧同构（重复强化语义），linkageNo 经 STOMP 头携带作联动标记（不改冻结载荷契约）
        messagingTemplate.convertAndSend(
                IotMessagingConstants.TOPIC_ALARM_PREFIX + alarm.getWardId(),
                buildAlarmPayload(alarm),
                java.util.Map.of("linkageNo", linkageNo));
        log.info(
                "联动强提醒帧已推送（带 linkage 标记头）：topic={}{}，alarmNo={}，linkageNo={}",
                IotMessagingConstants.TOPIC_ALARM_PREFIX,
                alarm.getWardId(),
                alarm.getAlarmNo(),
                linkageNo);
    }

    @Override
    public void pushDashboardSummary(DashboardSummaryVO summary) {
        // 全院主题不分病区：载荷 = DashboardSummaryVO（REST 快照兜底同构契约，summary 变更触发）
        messagingTemplate.convertAndSend(IotMessagingConstants.TOPIC_DASHBOARD_GLOBAL, summary);
        log.info(
                "全院运营摘要帧已推送：topic={}，online={}，offline={}，activeAlarms={}，storm={}",
                IotMessagingConstants.TOPIC_DASHBOARD_GLOBAL,
                summary.onlineCount(),
                summary.offlineCount(),
                summary.activeAlarmCount(),
                summary.stormActive());
    }

    /**
     * 摘要帧发送（窗口排空产物 → 契约载荷）：items 明细截至 {@link #SUMMARY_MAX_ITEMS} 防大
     * 消息（1009），条数字段保留窗口内真实累计值（订阅方以 count 为准——冻结契约延续）。
     *
     * @param frame 窗口排空帧，非空；来源：窗口聚合器 offer/drainExpired 产物
     */
    private void sendSummaryFrame(TelemetrySummaryAggregator.WindowFrame frame) {
        List<Item> items = new ArrayList<>(Math.min(frame.items().size(), SUMMARY_MAX_ITEMS));
        for (TelemetrySummaryAggregator.WindowItem windowItem : frame.items()) {
            if (items.size() >= SUMMARY_MAX_ITEMS) {
                break;
            }
            items.add(new Item(windowItem.deviceId(), windowItem.metricCode()));
        }
        TelemetrySummary summary = new TelemetrySummary((int) frame.count(), frame.occurredAtUpperBound(), items);
        messagingTemplate.convertAndSend(IotMessagingConstants.TOPIC_TELEMETRY_PREFIX + frame.wardId(), summary);
        log.info(
                "遥测摘要帧已推送（2s 窗口合并）：topic={}{}，count={}，items={}（截前 {} 条），occurredAtUpperBound={}",
                IotMessagingConstants.TOPIC_TELEMETRY_PREFIX,
                frame.wardId(),
                summary.count(),
                frame.items().size(),
                items.size(),
                summary.occurredAtUpperBound());
    }

    /**
     * 构造告警主题帧载荷（pushAlarm/pushLinkageNotify 共用）：与 iot.alarm.triggered 事件契约
     * record 同构，字段一一对应告警行快照。
     *
     * <p>occurredAt 漂移口径：取告警行 last_triggered_at 快照（非推送时刻）——新发行即触发时刻；
     * 重复触发经 IotAlarmMapper 计数 UPDATE 原地刷新 last_triggered_at，风暴补推/联动强化重推时
     * 该值晚于 MQ triggered 事件的首次 occurredAt（展示面容忍漂移，冻结载荷契约不改）。
     *
     * @param alarm 已落库告警实体，非空
     * @return triggered 契约载荷，非空
     */
    private static AlarmTriggeredPayload buildAlarmPayload(IotAlarmEntity alarm) {
        return new AlarmTriggeredPayload(
                alarm.getAlarmNo(),
                alarm.getDeviceId(),
                alarm.getPatientId(),
                alarm.getVisitId(),
                alarm.getWardId(),
                alarm.getAlarmLevel().getCode(),
                alarm.getMetricCode(),
                alarm.getTriggerValue(),
                alarm.getRuleId(),
                alarm.getLastTriggeredAt().toInstant());
    }

    // ---------------------------------------------------------------- SmartLifecycle：尾帧兜底排空线程

    /**
     * 启动尾帧兜底排空线程（SmartLifecycle，auto-startup）：固定周期调聚合器排空到期窗口——
     * 遥测停流场景下最后一批窗口帧无后续到批可触发排空，由本线程保证按时出帧（单实例自有
     * 状态自排空，不挂 ShedLock——互斥锁会阻止其余实例排空各自窗口）。
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        flushExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "iot-telemetry-summary-flush"));
        flushExecutor.scheduleWithFixedDelay(
                this::flushDueWindowsQuietly, FLUSH_POLL_MILLIS, FLUSH_POLL_MILLIS, TimeUnit.MILLISECONDS);
        log.info(
                "遥测摘要窗口兜底排空线程已启动：pollMillis={}，windowMillis={}",
                FLUSH_POLL_MILLIS,
                TelemetrySummaryAggregator.WINDOW.toMillis());
    }

    /**
     * 停机并关闭排空线程（不做停机补推：摘要帧为易逝展示数据，遥测行已落库，REST 兜底可查）。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService executor = flushExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                // 有界等待线程退出（interrupt 响应点在固定周期 sleep 中，秒级内必达）
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("遥测摘要窗口兜底排空线程已停机（尾帧不补推，REST 兜底）");
    }

    /**
     * 覆写意图：显式声明兜底排空线程随容器自动启动（SmartLifecycle 默认 true 的显式覆写，与
     * 攒批器/消费者同链路生命周期契约锚点）。
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 运行态标记：排空线程存续期间为 true（start/stop CAS 同源，供生命周期处理器校验）。 */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 单轮兜底排空（调度线程体；包级可见供同包生命周期单测直驱）：排空到期窗口逐帧推送；
     * 异常捕获留痕不打断调度周期。
     */
    void flushDueWindowsQuietly() {
        try {
            for (TelemetrySummaryAggregator.WindowFrame frame : summaryAggregator.drainExpired()) {
                sendSummaryFrame(frame);
            }
        } catch (RuntimeException e) {
            // 兜底排空失败留痕：窗口排空（出帧+移除）在聚合器锁内已完成，发送失败的已排空帧不补推
            // （易逝展示数据，REST 兜底）；异常不打断调度周期，下轮继续排空后续到期窗口
            log.error("遥测摘要窗口兜底排空失败（已排空帧不补推，下轮继续）：原因={}", e.getMessage(), e);
        }
    }
}

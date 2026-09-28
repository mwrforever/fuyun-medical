package com.fuyun.iotsimulator.scenario;

import com.fuyun.iotsimulator.telemetry.TelemetryPayloadBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 输液场景剧本（P2 PR-2 Task 14，状态机确定性推进）：模拟输液泵全周期业务状态——
 * {@code NORMAL}（余量 500ml 满速 80ml/h、体征临床值域）→ {@code DECAYING}（余量 &lt;100ml
 * 滴速线性衰减）→ {@code LOW}（余量 15ml 看板黄档）→ {@code CRITICAL}（余量 10ml 橙档 /
 * 5ml 红档，两档各公告一帧 status）→ {@code STARVED}（断流：滴速 0+余量 0+心率升入
 * 100~120 应激值域）→ 保持窗满后循环回 {@code NORMAL}；另支持命令暂停/恢复（PAUSED 覆盖态）。
 *
 * <p><b>确定性纪律</b>：时间流逝一律取注入 {@link Clock}（禁真实时钟），真实流逝 ×
 * 倍速换算为仿真流逝（IOTDA_SCENARIO_SPEED 倍速演示口径）；同 seed 构造逐帧内容可回放。
 * <b>推进模型</b>：每拍消耗量 = 拍起点滴速 × 仿真时长（小时）；帧内余量为消耗后快照、
 * 帧内滴速为当前泵速（即下一拍将应用的速率）——档位判定在消耗后按余量降序逐级比对
 * （DECAYING &lt;100ml / LOW ≤15ml / CRITICAL ≤10ml 与 ≤5ml / STARVED ≤0），单拍大幅流逝
 * （倍速演示）时连跨档位逐级公告不漏档，每档位每循环恰好公告一次。
 *
 * <p><b>滴速策略</b>：满速段（余量 ≥100ml）恒 80ml/h；衰减段滴速 = 0.8×余量（100ml 处
 * 与满速连续衔接、随余量线性衰减），并设 4ml/h 地板速率保证耗尽可达（纯比例衰减存在
 * 渐近尾、STARVED 永不可达）；STARVED 断流滴速 0；暂停覆盖态滴速 0、余量定格。
 *
 * <p><b>线程安全</b>：上报周期线程（nextFrame/drain）与命令接收线程（handleCommand）并发
 * 访问内部状态，状态机方法以实例锁互斥（单机演示无竞争，锁粒度取方法级最简）。
 */
public class InfusionScenario implements Scenario {

    /** 档位公告码：正常输注（循环起点/复位后） */
    public static final String PHASE_NORMAL = "NORMAL";

    /** 档位公告码：余量衰减段（<100ml） */
    public static final String PHASE_DECAYING = "DECAYING";

    /** 档位公告码：余量告急黄档（≤15ml，M16 输液看板 15-10-5ml 逐级播报对齐） */
    public static final String PHASE_LOW = "LOW";

    /** 档位公告码：余量危急（≤10ml 橙档 / ≤5ml 红档，两档各公告一帧） */
    public static final String PHASE_CRITICAL = "CRITICAL";

    /** 档位公告码：断流（余量 0，心率应激上升） */
    public static final String PHASE_STARVED = "STARVED";

    /** 档位公告码：命令暂停覆盖态 */
    public static final String PHASE_PAUSED = "PAUSED";

    /** 支持的命令名：暂停滴注（演示产品物模型命令定义；真实联调按 iot_product_command 白名单配置对齐） */
    public static final String CMD_PAUSE_INFUSION = "PAUSE_INFUSION";

    /** 支持的命令名：恢复滴注 */
    public static final String CMD_RESUME_INFUSION = "RESUME_INFUSION";

    /** 状态机阶段（不含暂停覆盖态——暂停以独立标志叠加在底层阶段之上） */
    public enum Phase {
        /** 满速输注段（余量 500→100ml） */
        NORMAL,
        /** 滴速线性衰减段（余量 <100ml） */
        DECAYING,
        /** 黄档（余量 ≤15ml） */
        LOW,
        /** 危急段（余量 ≤10ml 橙档 / ≤5ml 红档） */
        CRITICAL,
        /** 断流段（余量 0，保持窗满后循环复位） */
        STARVED
    }

    private static final Logger log = LoggerFactory.getLogger(InfusionScenario.class);

    /** 循环起点余量（ml）：满袋模拟 */
    private static final double INITIAL_VOLUME_ML = 500.0;

    /** 满速滴速（ml/h）：常规重力输注演示档 */
    private static final double FULL_RATE_ML_H = 80.0;

    /** DECAYING 进入阈值（ml）：余量低于此值滴速开始线性衰减 */
    private static final double DECAY_ENTRY_ML = 100.0;

    /** LOW 黄档阈值（ml）：M16 看板 15-10-5ml 逐级播报之黄档 */
    private static final double LOW_LEVEL_ML = 15.0;

    /** CRITICAL 橙档阈值（ml） */
    private static final double ORANGE_LEVEL_ML = 10.0;

    /** CRITICAL 红档阈值（ml） */
    private static final double RED_LEVEL_ML = 5.0;

    /** 衰减段地板滴速（ml/h）：保证余量耗尽可达（纯比例衰减渐近尾永不触底） */
    private static final double DECAY_FLOOR_RATE_ML_H = 4.0;

    /** STARVED 保持窗（仿真秒）：断流状态可观察窗，窗满循环复位 */
    private static final double STARVED_HOLD_SECONDS = 60.0;

    /** 心率临床值域下界（bpm，NORMAL~CRITICAL 段） */
    private static final int HEART_RATE_MIN = 60;

    /** 心率临床值域跨度（60~100） */
    private static final int HEART_RATE_SPAN = 41;

    /** 断流应激心率值域下界（bpm，STARVED 段轻度上升） */
    private static final int HEART_RATE_STRESSED_MIN = 100;

    /** 断流应激心率值域跨度（100~120） */
    private static final int HEART_RATE_STRESSED_SPAN = 21;

    /** 血氧演示值域下界（%） */
    private static final int SPO2_MIN = 95;

    /** 血氧演示值域跨度（95~100） */
    private static final int SPO2_SPAN = 6;

    /** 体温演示值域下界（℃） */
    private static final double BODY_TEMP_MIN_C = 36.0;

    /** 体温演示值域跨度（36.0~37.2） */
    private static final double BODY_TEMP_SPAN_C = 1.2;

    /** 物模型载荷构造器（帧组装委托；输液指标经显式值重载写入 Monitor 同服务） */
    private final TelemetryPayloadBuilder payloadBuilder;

    /** 注入时钟：状态机推进唯一时间源（禁真实时钟，确定性纪律） */
    private final Clock clock;

    /** 倍速（IOTDA_SCENARIO_SPEED）：真实流逝 ×倍速 = 仿真流逝 */
    private final double speed;

    /** 体征/体温确定性伪随机源（seed 派生自设备标识，同设备可回放） */
    private final Random random;

    /** 上一拍时刻（仿真流逝计算基准；构造期取时钟当前值） */
    private Instant lastTick;

    /** 当前余量（ml）：每拍按滴速×仿真时长递减 */
    private double volumeRemainingMl = INITIAL_VOLUME_ML;

    /** 底层状态机阶段（暂停覆盖态不占位，恢复后回到本阶段） */
    private Phase phase = Phase.NORMAL;

    /** 暂停覆盖态：命令暂停滴注时置位（滴速 0、余量定格、保持窗不推进） */
    private boolean paused;

    /** STARVED 保持窗已累计仿真秒数（循环复位判据） */
    private double starvedElapsedSeconds;

    /** 是否已产出首帧（初始档位 NORMAL 公告锚） */
    private boolean started;

    /** 本循环已公告档位（每档位每循环恰一次：CRITICAL 橙/红两档为两个独立公告点） */
    private final List<String> announced = new ArrayList<>();

    /** 待上行档位公告（drainPhaseTransitions 取出并清空） */
    private final List<String> pendingTransitions = new ArrayList<>();

    /**
     * 生产构造（ScenarioFactory 装配）：确定性序列由设备标识派生 seed。
     *
     * @param seed  伪随机种子（生产为 deviceId.hashCode 跨 JVM 稳定派生；测试传固定值回放）
     * @param speed 倍速（&gt;0），真实流逝 ×倍速 = 仿真流逝；来源：IOTDA_SCENARIO_SPEED
     * @param clock 时间源，非空；生产传 Clock.systemUTC()，测试注入可推进时钟
     */
    InfusionScenario(long seed, double speed, Clock clock) {
        this.payloadBuilder = new TelemetryPayloadBuilder(seed);
        this.random = new Random(seed);
        this.speed = speed;
        this.clock = clock;
        this.lastTick = clock.instant();
    }

    /** @return 剧本名 infusion（配置档词表） */
    @Override
    public String name() {
        return "infusion";
    }

    /**
     * 推进一拍并产出输液帧（Monitor 同服务五指标：体征 + 滴速/余量/体温）。
     *
     * <p>推进序：仿真流逝计算 → 首拍公告初始档位 → 状态推进（断流保持窗/暂停/余量消耗）
     * → 档位重估（逐级公告）→ 组帧。单帧失败由上行周期体吞并，状态机不因发布失败回退。
     *
     * @return 物模型 JSON 串，非空；心率 60~100（STARVED 100~120）、血氧 95~100、
     *         体温 36.0~37.2、滴速/余量按状态机当前值（一位小数）
     */
    @Override
    public synchronized String nextFrame() {
        Instant now = clock.instant();
        double elapsedSimSeconds = Duration.between(lastTick, now).toMillis() / 1000.0 * speed;
        lastTick = now;
        if (!started) {
            // 初始档位公告：让看板/状态链在循环起点即有 NORMAL 基准帧
            started = true;
            announce(PHASE_NORMAL);
        }
        advance(elapsedSimSeconds);
        return payloadBuilder.next(
                drawHeartRate(),
                SPO2_MIN + random.nextInt(SPO2_SPAN),
                round1(dripRateMlPerHour()),
                round1(volumeRemainingMl),
                round1(BODY_TEMP_MIN_C + random.nextDouble() * BODY_TEMP_SPAN_C));
    }

    /**
     * 取出并清空本周期累计的档位公告。
     *
     * @return 档位码列表（公告顺序 = 推进顺序），非空；无迁移时为空列表
     */
    @Override
    public synchronized List<String> drainPhaseTransitions() {
        List<String> drained = new ArrayList<>(pendingTransitions);
        pendingTransitions.clear();
        return drained;
    }

    /**
     * 剧本命令回调：暂停/恢复滴注（演示产品物模型命令词表，真实联调按命令白名单配置对齐）。
     *
     * @param commandName 命令名，可空；仅 {@link #CMD_PAUSE_INFUSION} / {@link #CMD_RESUME_INFUSION} 支持
     * @param parasJson   命令参数 JSON 文本，可空；暂停/恢复无参，忽略
     * @return true=命令执行成功（含重复执行的幂等成功）；false=命令名不支持（空值同）
     */
    @Override
    public synchronized boolean handleCommand(String commandName, String parasJson) {
        if (CMD_PAUSE_INFUSION.equals(commandName)) {
            return pause();
        }
        if (CMD_RESUME_INFUSION.equals(commandName)) {
            return resume();
        }
        // 未支持命令：交回执失败口径（result_code=1 → 平台归 FAILED 终态），不静默成功
        log.warn("剧本不支持命令：command={}（支持 {} / {}）", commandName, CMD_PAUSE_INFUSION, CMD_RESUME_INFUSION);
        return false;
    }

    /**
     * 当前底层阶段（观测用）：暂停覆盖态不占位——恢复后状态机回到本阶段继续推进。
     *
     * @return 底层状态机阶段，非空
     */
    public synchronized Phase phase() {
        return phase;
    }

    /**
     * 状态推进一拍：断流段累计保持窗（窗满循环复位）；暂停段冻结（余量定格、窗不推进）；
     * 其余段按拍起点滴速消耗余量并重估档位。
     *
     * @param elapsedSimSeconds 本拍仿真流逝秒数（非负）
     */
    private void advance(double elapsedSimSeconds) {
        if (paused) {
            // 暂停覆盖：余量定格、断流保持窗冻结（时钟照常流逝，恢复后从冻结点续推）
            return;
        }
        if (phase == Phase.STARVED) {
            // 断流保持窗：窗满循环复位（回满袋、回满速、心率回临床值域）
            starvedElapsedSeconds += elapsedSimSeconds;
            if (starvedElapsedSeconds >= STARVED_HOLD_SECONDS) {
                restartCycle();
            }
            return;
        }
        double hours = elapsedSimSeconds / 3600.0;
        // 本拍消耗量 = 拍起点滴速 × 仿真时长（小时）；余量触底即钳 0（负余量无业务含义）
        volumeRemainingMl -= dripRateMlPerHour() * hours;
        if (volumeRemainingMl <= 0.0) {
            volumeRemainingMl = 0.0;
        }
        reevaluatePhase();
    }

    /**
     * 档位重估：按余量降序逐级比对阈值并公告新跨入档位——DECAYING(&lt;100) / LOW(≤15) /
     * CRITICAL 橙档(≤10) / CRITICAL 红档(≤5) / STARVED(≤0)；单拍连跨时公告按序累计，
     * 每档位每循环恰一次。
     */
    private void reevaluatePhase() {
        if (volumeRemainingMl < DECAY_ENTRY_ML && phase == Phase.NORMAL) {
            phase = Phase.DECAYING;
            announce(PHASE_DECAYING);
        }
        if (volumeRemainingMl <= LOW_LEVEL_ML && !announced.contains(PHASE_LOW)) {
            phase = Phase.LOW;
            announce(PHASE_LOW);
        }
        if (volumeRemainingMl <= ORANGE_LEVEL_ML && !announced.contains(PHASE_CRITICAL)) {
            // 橙档（10ml）：CRITICAL 第一次公告
            phase = Phase.CRITICAL;
            announce(PHASE_CRITICAL);
        }
        if (volumeRemainingMl <= RED_LEVEL_ML
                && announced.stream().filter(PHASE_CRITICAL::equals).count() < 2) {
            // 红档（5ml）：CRITICAL 第二次公告——两档各输出一帧（公告点独立于阶段名）
            announce(PHASE_CRITICAL);
        }
        if (volumeRemainingMl <= 0.0 && !announced.contains(PHASE_STARVED)) {
            phase = Phase.STARVED;
            starvedElapsedSeconds = 0.0;
            announce(PHASE_STARVED);
            log.info("输液剧本断流：余量归零进入 STARVED，保持 {} 仿真秒后循环复位", STARVED_HOLD_SECONDS);
        }
    }

    /**
     * 当前滴速（ml/h）：暂停 0；断流 0；满速段 80；衰减段 max(4, 0.8×余量) 线性衰减。
     *
     * @return 当前泵速（ml/h，非负）
     */
    private double dripRateMlPerHour() {
        if (paused || phase == Phase.STARVED) {
            return 0.0;
        }
        if (phase == Phase.NORMAL) {
            return FULL_RATE_ML_H;
        }
        // 衰减段：滴速随余量线性衰减（100ml 处 80ml/h 与满速连续衔接），地板速率保耗尽可达
        return Math.max(DECAY_FLOOR_RATE_ML_H, FULL_RATE_ML_H * volumeRemainingMl / DECAY_ENTRY_ML);
    }

    /** 暂停滴注：公告 PAUSED（重复暂停幂等——不重复公告）； @return 恒 true（命令执行成功） */
    private boolean pause() {
        if (!paused) {
            paused = true;
            announce(PHASE_PAUSED);
            log.info("输液剧本已暂停：余量定格 {}ml", volumeRemainingMl);
        }
        return true;
    }

    /**
     * 恢复滴注：公告底层档位（PAUSED→底层阶段属档位变化）；未暂停时幂等零公告。
     *
     * @return 恒 true（命令执行成功）
     */
    private boolean resume() {
        if (paused) {
            paused = false;
            announce(phase.name());
            log.info("输液剧本已恢复：回到档位 {}", phase.name());
        }
        return true;
    }

    /** 循环复位（STARVED 保持窗满）：余量回满袋、阶段回 NORMAL、公告复位、清空档位记录 */
    private void restartCycle() {
        volumeRemainingMl = INITIAL_VOLUME_ML;
        phase = Phase.NORMAL;
        starvedElapsedSeconds = 0.0;
        announced.clear();
        announce(PHASE_NORMAL);
        log.info("输液剧本循环复位：余量回 500ml，滴速回满速 {}ml/h", FULL_RATE_ML_H);
    }

    /**
     * 公告一帧档位迁移（周期线程经 DeviceStatusReporter 转 status 属性帧上行）。
     *
     * @param phaseCode 档位码，非空
     */
    private void announce(String phaseCode) {
        announced.add(phaseCode);
        pendingTransitions.add(phaseCode);
    }

    /**
     * 抽取当前阶段心率：STARVED 段取应激值域 100~120，其余段取临床值域 60~100。
     *
     * @return 心率（bpm，整数）
     */
    private int drawHeartRate() {
        if (phase == Phase.STARVED) {
            // 断流应激：心率轻度上升至 100~120（临床应激值域）
            return HEART_RATE_STRESSED_MIN + random.nextInt(HEART_RATE_STRESSED_SPAN);
        }
        return HEART_RATE_MIN + random.nextInt(HEART_RATE_SPAN);
    }

    /**
     * 一位小数取整（滴速/余量/体温演示精度；JSON 数值稳定可比对）。
     *
     * @param value 原值
     * @return 一位小数值
     */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

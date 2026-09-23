package com.fuyun.nursing.constants;

import com.fuyun.nursing.enums.RiskLevel;
import com.fuyun.nursing.enums.ScaleType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 护理评估五量表定义冻结常量（05-nursing Spec §4 + 调研依据 9 量表体系）：BRADEN/MORSE/NRS/
 * BARTHEL/MEWS 的条目词表、取值域、判级阈值与联动策略唯一权威来源（Spec :249「量表为模块专业
 * 配置非国标字典」——不落字典表，改阈值属宪法级契约变更）。阈值逐值冻结，方向随量表不统一：
 * BRADEN/BARTHEL 低分高危、MORSE/NRS/MEWS 高分高危；各判级函数对量表可达总分域穷尽划分，
 * 无不可判级分支。CUSTOM 自定义量表引擎归 P2（词表外 NS-1009 拒绝）。
 */
public final class NursingScaleConstants {

    /** 总分规则 code：条目分值求和（五量表统一，V806 total_rule 出参承载值） */
    public static final String TOTAL_RULE_SUM = "SUM";

    /** 高危风险标识：BRADEN 高危回写 nursing_ward_patient.risk_flags 的压疮标识 */
    public static final String FLAG_PRESSURE = "PRESSURE";

    /** 高危风险标识：MORSE 高危回写 nursing_ward_patient.risk_flags 的跌倒标识 */
    public static final String FLAG_FALL = "FALL";

    /** 五量表定义（LinkedHashMap 保持 BRADEN→MORSE→NRS→BARTHEL→MEWS 冻结展示序） */
    private static final Map<ScaleType, ScaleDefinition> DEFINITIONS = buildDefinitions();

    private NursingScaleConstants() {}

    /**
     * 取指定量表的冻结定义。
     *
     * @param scaleType 量表类型，非空；来源：创建入参经 ScaleType.fromCode 归一后的枚举
     * @return 量表定义，非空；五值全量登记，查无即编程错误（调用方先经词表校验）
     */
    public static ScaleDefinition definition(ScaleType scaleType) {
        return DEFINITIONS.get(scaleType);
    }

    /**
     * 全量量表定义（评估表单渲染唯一数据源 GET /assessment-scales 的装配来源）。
     *
     * @return 五量表定义清单，非空；按冻结展示序（BRADEN→MORSE→NRS→BARTHEL→MEWS）
     */
    public static Collection<ScaleDefinition> definitions() {
        return DEFINITIONS.values();
    }

    /**
     * 风险等级 → 复评周期（小时）：HIGH 24h / MEDIUM 72h / LOW 168h（调研依据 9 量表体系
     * 复评口径冻结值；next_assess_plan = assessedAt + 周期）。
     *
     * @param level 风险等级，非空；来源：判级结果枚举
     * @return 复评周期小时数，恒为 24/72/168 之一
     */
    public static long reassessHours(RiskLevel level) {
        // 三级判级结果各有冻结周期：高危 24 小时（日常复评红线）、中危 72 小时、低危 168 小时（周评）
        return switch (level) {
            case HIGH -> 24L;
            case MEDIUM -> 72L;
            case LOW -> 168L;
        };
    }

    /**
     * 量表类型 → 床旁风险标识回写映射（联动冻结口径）：仅 BRADEN→PRESSURE（压疮）、
     * MORSE→FALL（跌倒）回写 nursing_ward_patient.risk_flags；MEWS/NRS/BARTHEL 判级结果
     * 只随评估单承载不回写床旁标识（TUBE 管路标识属执行域管路管理，归 P2）。
     *
     * @param scaleType 量表类型，非空
     * @return 风险标识 code；无回写映射的量表返回 null（调用方判空跳过回写）
     */
    public static String riskFlagOf(ScaleType scaleType) {
        return switch (scaleType) {
            case BRADEN -> FLAG_PRESSURE;
            case MORSE -> FLAG_FALL;
            default -> null;
        };
    }

    // ===================== 五量表冻结定义 =====================

    /** 五量表定义装配（静态初始化一次；逐量表独立私有方法保证阈值逐值可注释溯源）。 */
    private static Map<ScaleType, ScaleDefinition> buildDefinitions() {
        Map<ScaleType, ScaleDefinition> definitions = new LinkedHashMap<>();
        definitions.put(ScaleType.BRADEN, braden());
        definitions.put(ScaleType.MORSE, morse());
        definitions.put(ScaleType.NRS, nrs());
        definitions.put(ScaleType.BARTHEL, barthel());
        definitions.put(ScaleType.MEWS, mews());
        return definitions;
    }

    /**
     * BRADEN 压疮风险评估：6 条目（感知/潮湿/活动/移动/营养各 1–4 分，摩擦剪切 1–3 分），
     * 总分 6–23。判级阈值冻结（调研依据 9 + Spec 量表通用口径）：
     * HIGH ≤12（含 ≤9 极高危档）/ MEDIUM 13–18 / LOW ≥19。
     */
    private static ScaleDefinition braden() {
        return new ScaleDefinition(
                ScaleType.BRADEN,
                List.of("PERCEPTION", "MOISTURE", "ACTIVITY", "MOBILITY", "NUTRITION", "FRICTION"),
                List.of("知觉感受", "潮湿程度", "活动能力", "移动能力", "营养摄取", "摩擦力与剪切力"),
                Map.ofEntries(
                        Map.entry("PERCEPTION", range(1, 4)),
                        Map.entry("MOISTURE", range(1, 4)),
                        Map.entry("ACTIVITY", range(1, 4)),
                        Map.entry("MOBILITY", range(1, 4)),
                        Map.entry("NUTRITION", range(1, 4)),
                        Map.entry("FRICTION", range(1, 3))),
                TOTAL_RULE_SUM,
                // ≤12 高危（≤9 极高危并入 HIGH 档，不另设等级）/ 13–18 中危（18 为轻度风险上沿）/ ≥19 低危
                total -> total <= 12 ? RiskLevel.HIGH : total <= 18 ? RiskLevel.MEDIUM : RiskLevel.LOW);
    }

    /**
     * MORSE 跌倒风险评估：6 条目（跌倒史 0–25 / 多项诊断 0–15 / 行走辅助 0–30 / 静脉输液 0–20 /
     * 步态 0–20 / 认知 0–15），总分 0–125。判级阈值冻结（调研依据 9 + Spec 量表通用口径）：
     * HIGH ≥45 / MEDIUM 25–44 / LOW &lt;25。
     */
    private static ScaleDefinition morse() {
        return new ScaleDefinition(
                ScaleType.MORSE,
                List.of("FALL_HISTORY", "SECOND_DIAGNOSIS", "AMBULATORY_AID", "IV_THERAPY", "GAIT", "MENTAL_STATUS"),
                List.of("三个月内跌倒史", "超过一个诊断", "行走辅助", "静脉输液/置管", "步态状态", "认知状态"),
                Map.ofEntries(
                        Map.entry("FALL_HISTORY", range(0, 25)),
                        Map.entry("SECOND_DIAGNOSIS", range(0, 15)),
                        Map.entry("AMBULATORY_AID", range(0, 30)),
                        Map.entry("IV_THERAPY", range(0, 20)),
                        Map.entry("GAIT", range(0, 20)),
                        Map.entry("MENTAL_STATUS", range(0, 15))),
                TOTAL_RULE_SUM,
                // ≥45 高危（跌倒干预红线）/ 25–44 中危（44 为高危下沿临界）/ <25 低危
                total -> total >= 45 ? RiskLevel.HIGH : total >= 25 ? RiskLevel.MEDIUM : RiskLevel.LOW);
    }

    /**
     * NRS 疼痛数字评分：单条目 0–10。判级阈值冻结（调研依据 9 + Spec 量表通用口径）：
     * HIGH ≥7 / MEDIUM 4–6 / LOW &lt;4。
     */
    private static ScaleDefinition nrs() {
        return new ScaleDefinition(
                ScaleType.NRS,
                List.of("PAIN_SCORE"),
                List.of("疼痛评分"),
                Map.ofEntries(Map.entry("PAIN_SCORE", range(0, 10))),
                TOTAL_RULE_SUM,
                // ≥7 高危（剧烈疼痛干预红线）/ 4–6 中危（中度疼痛）/ <4 低危
                total -> total >= 7 ? RiskLevel.HIGH : total >= 4 ? RiskLevel.MEDIUM : RiskLevel.LOW);
    }

    /**
     * BARTHEL 自理能力评估：10 条目（进食 0–10 / 洗澡 0–5 / 修饰 0–5 / 穿衣 0–10 / 大便控制 0–10 /
     * 小便控制 0–10 / 如厕 0–10 / 床椅转移 0–15 / 平地行走 0–15 / 上下楼梯 0–10），总分 0–100。
     * 判级阈值冻结（调研依据 9 + Spec 量表通用口径）：HIGH ≤40 / MEDIUM 41–60 / LOW ≥61
     * （自理能力量表低分高危，与 BRADEN 同向）。
     */
    private static ScaleDefinition barthel() {
        return new ScaleDefinition(
                ScaleType.BARTHEL,
                List.of(
                        "FEEDING",
                        "BATHING",
                        "GROOMING",
                        "DRESSING",
                        "BOWELS",
                        "BLADDER",
                        "TOILETING",
                        "TRANSFER",
                        "WALKING",
                        "STAIRS"),
                List.of("进食", "洗澡", "修饰", "穿衣", "大便控制", "小便控制", "如厕", "床椅转移", "平地行走", "上下楼梯"),
                Map.ofEntries(
                        Map.entry("FEEDING", range(0, 10)),
                        Map.entry("BATHING", range(0, 5)),
                        Map.entry("GROOMING", range(0, 5)),
                        Map.entry("DRESSING", range(0, 10)),
                        Map.entry("BOWELS", range(0, 10)),
                        Map.entry("BLADDER", range(0, 10)),
                        Map.entry("TOILETING", range(0, 10)),
                        Map.entry("TRANSFER", range(0, 15)),
                        Map.entry("WALKING", range(0, 15)),
                        Map.entry("STAIRS", range(0, 10))),
                TOTAL_RULE_SUM,
                // ≤40 高危（重度依赖）/ 41–60 中危（中度依赖，41 为高危上沿临界）/ ≥61 低危（轻度依赖及以上）
                total -> total <= 40 ? RiskLevel.HIGH : total <= 60 ? RiskLevel.MEDIUM : RiskLevel.LOW);
    }

    /**
     * MEWS 早期预警评分：5 参数（收缩压 0–3 / 心率 0–3 / 呼吸 0–3 / 体温 0–2 / 意识 0–3），
     * 总分 0–14。判级阈值冻结（调研依据 9 + Spec 量表通用口径）：HIGH ≥5 / MEDIUM 3–4 / LOW ≤2。
     */
    private static ScaleDefinition mews() {
        return new ScaleDefinition(
                ScaleType.MEWS,
                List.of("SBP", "HR", "RR", "TEMP", "CONSCIOUSNESS"),
                List.of("收缩压", "心率", "呼吸频率", "体温", "意识状态"),
                Map.ofEntries(
                        Map.entry("SBP", range(0, 3)),
                        Map.entry("HR", range(0, 3)),
                        Map.entry("RR", range(0, 3)),
                        Map.entry("TEMP", range(0, 2)),
                        Map.entry("CONSCIOUSNESS", range(0, 3))),
                TOTAL_RULE_SUM,
                // ≥5 高危（触发早期预警干预红线）/ 3–4 中危（加强监测）/ ≤2 低危
                total -> total >= 5 ? RiskLevel.HIGH : total >= 3 ? RiskLevel.MEDIUM : RiskLevel.LOW);
    }

    /**
     * 条目取值域构造：闭区间 [min, max] 全量枚举（取值范围校验与前端选项渲染同源，
     * 避免两处维护漂移）。
     *
     * @param min 条目最小分值（含）
     * @param max 条目最大分值（含）
     * @return 允许分值升序清单，非空
     */
    private static List<Integer> range(int min, int max) {
        return IntStream.rangeClosed(min, max).boxed().collect(Collectors.toUnmodifiableList());
    }
}

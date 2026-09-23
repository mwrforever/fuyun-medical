package com.fuyun.nursing.constants;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.vo.AbnormalItemVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 生命体征阈值常量表与静态判定（Global Constraints 19 逐值落死，Task 5 冻结面——后续任务与
 * IT 依赖的可执行阈值权威）。两类阈值：
 * <ul>
 * <li><b>正常范围</b>（观察行归集判定）：体温 36.0–37.2℃／脉搏 60–100 次/分／呼吸 12–20 次/分／
 *     收缩压 90–140 mmHg／舒张压 60–90 mmHg／血氧 ≥95%／疼痛 NRS &lt;4——边界含端点（闭区间，
 *     疼痛为上开区间），任一已测指标越界即 abnormal（Spec :64「符合条件」的量化）；
 * <li><b>生理极限</b>（录入拒收闸门）：体温 34–43／脉搏 20–200／呼吸 5–60／收缩压 40–300／
 *     舒张压 20–200／血氧 ≥50——越界 400 拒收 NS-1005（医疗安全护栏，禁脏值入库）。
 * </ul>
 * 疼痛评分无生理极限约束（NRS 表单定义域 0-10 已约束）；体重/身高不参与任何阈值判定。
 * 异常项描述文本形态冻结：「体温 38.6℃ 高于正常范围 36.0–37.2℃」（Task 11 IT 断言锚）。
 * 线程安全：纯静态无状态工具类。
 */
public final class NursingVitalThresholds {

    // ===================== 正常范围（闭区间边界，疼痛为上开区间） =====================

    /** 体温正常下限（℃） */
    public static final BigDecimal TEMPERATURE_NORMAL_LOW = new BigDecimal("36.0");

    /** 体温正常上限（℃） */
    public static final BigDecimal TEMPERATURE_NORMAL_HIGH = new BigDecimal("37.2");

    /** 脉搏正常下限（次/分） */
    public static final int PULSE_NORMAL_LOW = 60;

    /** 脉搏正常上限（次/分） */
    public static final int PULSE_NORMAL_HIGH = 100;

    /** 呼吸正常下限（次/分） */
    public static final int RESPIRATION_NORMAL_LOW = 12;

    /** 呼吸正常上限（次/分） */
    public static final int RESPIRATION_NORMAL_HIGH = 20;

    /** 收缩压正常下限（mmHg） */
    public static final int SYSTOLIC_NORMAL_LOW = 90;

    /** 收缩压正常上限（mmHg） */
    public static final int SYSTOLIC_NORMAL_HIGH = 140;

    /** 舒张压正常下限（mmHg） */
    public static final int DIASTOLIC_NORMAL_LOW = 60;

    /** 舒张压正常上限（mmHg） */
    public static final int DIASTOLIC_NORMAL_HIGH = 90;

    /** 血氧正常下限（%，≥） */
    public static final int SPO2_NORMAL_LOW = 95;

    /** 疼痛正常上限（NRS，开区间 &lt;4 为正常） */
    public static final int PAIN_NORMAL_HIGH_EXCLUSIVE = 4;

    // ===================== 生理极限（录入拒收闸门，越界 400 拒收 NS-1005） =====================

    /** 体温生理极限下限（℃） */
    public static final BigDecimal TEMPERATURE_LIMIT_LOW = new BigDecimal("34");

    /** 体温生理极限上限（℃） */
    public static final BigDecimal TEMPERATURE_LIMIT_HIGH = new BigDecimal("43");

    /** 脉搏生理极限下限（次/分） */
    public static final int PULSE_LIMIT_LOW = 20;

    /** 脉搏生理极限上限（次/分） */
    public static final int PULSE_LIMIT_HIGH = 200;

    /** 呼吸生理极限下限（次/分） */
    public static final int RESPIRATION_LIMIT_LOW = 5;

    /** 呼吸生理极限上限（次/分） */
    public static final int RESPIRATION_LIMIT_HIGH = 60;

    /** 收缩压生理极限下限（mmHg） */
    public static final int SYSTOLIC_LIMIT_LOW = 40;

    /** 收缩压生理极限上限（mmHg） */
    public static final int SYSTOLIC_LIMIT_HIGH = 300;

    /** 舒张压生理极限下限（mmHg） */
    public static final int DIASTOLIC_LIMIT_LOW = 20;

    /** 舒张压生理极限上限（mmHg） */
    public static final int DIASTOLIC_LIMIT_HIGH = 200;

    /** 血氧生理极限下限（%，≥） */
    public static final int SPO2_LIMIT_LOW = 50;

    /** 私有构造器（纯静态工具类，A.2-6） */
    private NursingVitalThresholds() {}

    /**
     * 全项正常判定：全部已测指标均落在正常范围内（未测项不参与判定，全未测视为正常）。
     * 与 {@link #abnormalItems(VitalSignValues)} 同一判定内核，二者恒一致。
     *
     * @param v 阈值判定入参，可空（null 视为无任何已测指标 → 正常）
     * @return true=全部已测指标正常（观察行归集走合并分支）；false=任一指标越正常范围
     */
    public static boolean isWithinNormalRange(VitalSignValues v) {
        return abnormalItems(v).isEmpty();
    }

    /**
     * 异常项清单：逐指标比对正常范围，越界项生成异常描述（文本形态冻结：
     * 「体温 38.6℃ 高于正常范围 36.0–37.2℃」；单侧阈值项为「血氧 92% 低于正常范围 ≥95%」形态）。
     * 未测项（null）不参与判定不产生条目；清单非空即 abnormal=true（观察行独立落行依据）。
     *
     * @param v 阈值判定入参，可空（null 返回空清单）
     * @return 异常项清单（无异常返回空清单，非 null）；按体温/脉搏/呼吸/血压/血氧/疼痛固定序
     */
    public static List<AbnormalItemVO> abnormalItems(VitalSignValues v) {
        List<AbnormalItemVO> items = new ArrayList<>();
        if (v == null) {
            return items;
        }
        // 体温：36.0 ≤ t ≤ 37.2（闭区间），越上界「高于」、越下界「低于」
        BigDecimal temperature = v.temperature();
        if (temperature != null) {
            if (temperature.compareTo(TEMPERATURE_NORMAL_HIGH) > 0) {
                items.add(new AbnormalItemVO(
                        "体温",
                        "体温 " + temperature.toPlainString() + "℃ 高于正常范围 "
                                + TEMPERATURE_NORMAL_LOW.toPlainString() + "–" + TEMPERATURE_NORMAL_HIGH.toPlainString()
                                + "℃"));
            } else if (temperature.compareTo(TEMPERATURE_NORMAL_LOW) < 0) {
                items.add(new AbnormalItemVO(
                        "体温",
                        "体温 " + temperature.toPlainString() + "℃ 低于正常范围 "
                                + TEMPERATURE_NORMAL_LOW.toPlainString() + "–" + TEMPERATURE_NORMAL_HIGH.toPlainString()
                                + "℃"));
            }
        }
        // 脉搏：60 ≤ p ≤ 100（次/分）
        if (v.pulse() != null) {
            if (v.pulse() > PULSE_NORMAL_HIGH) {
                items.add(new AbnormalItemVO(
                        "脉搏",
                        "脉搏 " + v.pulse() + " 次/分 高于正常范围 " + PULSE_NORMAL_LOW + "–" + PULSE_NORMAL_HIGH + " 次/分"));
            } else if (v.pulse() < PULSE_NORMAL_LOW) {
                items.add(new AbnormalItemVO(
                        "脉搏",
                        "脉搏 " + v.pulse() + " 次/分 低于正常范围 " + PULSE_NORMAL_LOW + "–" + PULSE_NORMAL_HIGH + " 次/分"));
            }
        }
        // 呼吸：12 ≤ r ≤ 20（次/分）
        if (v.respiration() != null) {
            if (v.respiration() > RESPIRATION_NORMAL_HIGH) {
                items.add(new AbnormalItemVO(
                        "呼吸",
                        "呼吸 " + v.respiration() + " 次/分 高于正常范围 " + RESPIRATION_NORMAL_LOW + "–"
                                + RESPIRATION_NORMAL_HIGH + " 次/分"));
            } else if (v.respiration() < RESPIRATION_NORMAL_LOW) {
                items.add(new AbnormalItemVO(
                        "呼吸",
                        "呼吸 " + v.respiration() + " 次/分 低于正常范围 " + RESPIRATION_NORMAL_LOW + "–"
                                + RESPIRATION_NORMAL_HIGH + " 次/分"));
            }
        }
        // 收缩压：90 ≤ sys ≤ 140（mmHg）
        if (v.systolicBp() != null) {
            if (v.systolicBp() > SYSTOLIC_NORMAL_HIGH) {
                items.add(new AbnormalItemVO(
                        "收缩压",
                        "收缩压 " + v.systolicBp() + " mmHg 高于正常范围 " + SYSTOLIC_NORMAL_LOW + "–" + SYSTOLIC_NORMAL_HIGH
                                + " mmHg"));
            } else if (v.systolicBp() < SYSTOLIC_NORMAL_LOW) {
                items.add(new AbnormalItemVO(
                        "收缩压",
                        "收缩压 " + v.systolicBp() + " mmHg 低于正常范围 " + SYSTOLIC_NORMAL_LOW + "–" + SYSTOLIC_NORMAL_HIGH
                                + " mmHg"));
            }
        }
        // 舒张压：60 ≤ dia ≤ 90（mmHg）
        if (v.diastolicBp() != null) {
            if (v.diastolicBp() > DIASTOLIC_NORMAL_HIGH) {
                items.add(new AbnormalItemVO(
                        "舒张压",
                        "舒张压 " + v.diastolicBp() + " mmHg 高于正常范围 " + DIASTOLIC_NORMAL_LOW + "–" + DIASTOLIC_NORMAL_HIGH
                                + " mmHg"));
            } else if (v.diastolicBp() < DIASTOLIC_NORMAL_LOW) {
                items.add(new AbnormalItemVO(
                        "舒张压",
                        "舒张压 " + v.diastolicBp() + " mmHg 低于正常范围 " + DIASTOLIC_NORMAL_LOW + "–" + DIASTOLIC_NORMAL_HIGH
                                + " mmHg"));
            }
        }
        // 血氧：≥95%（单侧下限，越界恒为「低于」）
        if (v.spo2() != null && v.spo2() < SPO2_NORMAL_LOW) {
            items.add(new AbnormalItemVO("血氧", "血氧 " + v.spo2() + "% 低于正常范围 ≥" + SPO2_NORMAL_LOW + "%"));
        }
        // 疼痛：NRS <4 为正常（上开区间），越界恒为「高于」
        if (v.painScore() != null && v.painScore() >= PAIN_NORMAL_HIGH_EXCLUSIVE) {
            items.add(new AbnormalItemVO(
                    "疼痛", "疼痛 NRS " + v.painScore() + " 分 高于正常范围 <" + PAIN_NORMAL_HIGH_EXCLUSIVE + " 分"));
        }
        return items;
    }

    /**
     * 生理极限拒收闸门：任一已测指标越生理极限即抛 NS-1005（400，消息含「生理极限」与越界项
     * 明细）；全部已测指标在极限内（或全未测）静默通过。P1 录入链第一步强制调用（脏值不入库）。
     *
     * @param v 阈值判定入参，可空（null 视为无指标，直接通过）
     * @throws BizException NS-1005（400 体征超生理极限，拒收；消息含「生理极限」与首个越界项明细）
     */
    public static void assertWithinPhysiologicalLimit(VitalSignValues v) {
        if (v == null) {
            return;
        }
        // 体温极限 34–43℃：越界即拒（医疗安全护栏，脏值不入库）
        BigDecimal temperature = v.temperature();
        if (temperature != null
                && (temperature.compareTo(TEMPERATURE_LIMIT_LOW) < 0
                        || temperature.compareTo(TEMPERATURE_LIMIT_HIGH) > 0)) {
            throw outOfRange("体温 " + temperature.toPlainString() + "℃ 超出范围 " + TEMPERATURE_LIMIT_LOW.toPlainString()
                    + "–" + TEMPERATURE_LIMIT_HIGH.toPlainString() + "℃");
        }
        // 脉搏极限 20–200 次/分
        if (v.pulse() != null && (v.pulse() < PULSE_LIMIT_LOW || v.pulse() > PULSE_LIMIT_HIGH)) {
            throw outOfRange("脉搏 " + v.pulse() + " 次/分 超出范围 " + PULSE_LIMIT_LOW + "–" + PULSE_LIMIT_HIGH + " 次/分");
        }
        // 呼吸极限 5–60 次/分
        if (v.respiration() != null
                && (v.respiration() < RESPIRATION_LIMIT_LOW || v.respiration() > RESPIRATION_LIMIT_HIGH)) {
            throw outOfRange("呼吸 " + v.respiration() + " 次/分 超出范围 " + RESPIRATION_LIMIT_LOW + "–"
                    + RESPIRATION_LIMIT_HIGH + " 次/分");
        }
        // 收缩压极限 40–300 mmHg
        if (v.systolicBp() != null && (v.systolicBp() < SYSTOLIC_LIMIT_LOW || v.systolicBp() > SYSTOLIC_LIMIT_HIGH)) {
            throw outOfRange(
                    "收缩压 " + v.systolicBp() + " mmHg 超出范围 " + SYSTOLIC_LIMIT_LOW + "–" + SYSTOLIC_LIMIT_HIGH + " mmHg");
        }
        // 舒张压极限 20–200 mmHg
        if (v.diastolicBp() != null
                && (v.diastolicBp() < DIASTOLIC_LIMIT_LOW || v.diastolicBp() > DIASTOLIC_LIMIT_HIGH)) {
            throw outOfRange("舒张压 " + v.diastolicBp() + " mmHg 超出范围 " + DIASTOLIC_LIMIT_LOW + "–" + DIASTOLIC_LIMIT_HIGH
                    + " mmHg");
        }
        // 血氧极限 ≥50%
        if (v.spo2() != null && v.spo2() < SPO2_LIMIT_LOW) {
            throw outOfRange("血氧 " + v.spo2() + "% 低于极限 ≥" + SPO2_LIMIT_LOW + "%");
        }
        // 疼痛评分无生理极限约束（NRS 表单定义域 0-10 已约束）；体重/身高不参与判定
    }

    /** 极限越界异常装配（NS-1005，400；消息含「生理极限」为测试与前端文案锚）。 */
    private static BizException outOfRange(String detail) {
        return new BizException(NursingErrorCode.VITAL_OUT_OF_RANGE, HttpStatus.BAD_REQUEST, "体征超出生理极限，拒收：" + detail);
    }
}

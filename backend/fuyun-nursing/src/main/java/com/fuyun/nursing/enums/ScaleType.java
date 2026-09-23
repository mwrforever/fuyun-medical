package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理评估量表类型五值（nursing.nursing_assessment.scale_type 值域，05-nursing Spec §4 +
 * 调研依据 9 量表体系）：BRADEN 压疮风险 / MORSE 跌倒风险 / NRS 疼痛评分 / BARTHEL 自理能力 /
 * MEWS 早期预警。量表定义（条目/取值域/判级阈值）以模块内 Java 常量承载（Spec :249「量表为
 * 模块专业配置非国标字典」，NursingScaleConstants 唯一来源）；CUSTOM 自定义量表引擎归 P2
 * （词表外值经 fromCode 判空拒 NS-1009，不做裸异常）。
 */
public enum ScaleType {

    /** BRADEN 压疮风险评估（6 条目，总分 6–23；高危回写 PRESSURE 风险标识） */
    BRADEN("BRADEN"),

    /** MORSE 跌倒风险评估（6 条目，总分 0–125；高危回写 FALL 风险标识） */
    MORSE("MORSE"),

    /** NRS 疼痛数字评分（单条目 0–10；不回写风险标识） */
    NRS("NRS"),

    /** BARTHEL 自理能力评估（10 条目，总分 0–100；不回写风险标识） */
    BARTHEL("BARTHEL"),

    /** MEWS 早期预警评分（5 参数，总分 0–14；不回写风险标识） */
    MEWS("MEWS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ScaleType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧（创建入参显式格式校验用；CUSTOM 等词表外值返回 null 交调用方拒 NS-1009，
     * 自定义量表引擎归 P2，禁裸转换）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝）
     */
    public static ScaleType fromCode(String code) {
        for (ScaleType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

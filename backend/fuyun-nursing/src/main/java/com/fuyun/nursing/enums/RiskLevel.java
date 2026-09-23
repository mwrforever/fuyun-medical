package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理评估风险等级三值（nursing.nursing_assessment.risk_level 值域）：HIGH 高风险 /
 * MEDIUM 中风险 / LOW 低风险。判级阈值随量表不同而不同（NursingScaleConstants 冻结清单，
 * 方向不统一——BRADEN/BARTHEL 低分高危、MORSE/NRS/MEWS 高分高危）；HIGH 触发防范任务
 * 生成与床旁风险标识回写（仅 BRADEN→PRESSURE、MORSE→FALL），并按等级驱动复评周期
 * （HIGH 24h / MEDIUM 72h / LOW 168h）。
 */
public enum RiskLevel {

    /** 高风险（触发防范任务生成 + 风险标识回写；复评周期 24 小时） */
    HIGH("HIGH"),

    /** 中风险（仅登记判级结果与复评计划；复评周期 72 小时） */
    MEDIUM("MEDIUM"),

    /** 低风险（仅登记判级结果与复评计划；复评周期 168 小时） */
    LOW("LOW");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RiskLevel(String code) {
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
}

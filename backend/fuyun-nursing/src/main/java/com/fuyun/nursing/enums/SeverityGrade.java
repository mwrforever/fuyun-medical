package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 不良事件严重度等级五值（V1107 adverse_event.severity_grade 值域，05-nursing Spec
 * FU-M05-09）：A 无害 ~ E 死亡，与严重度分级（I~IV）正交——上报表单双维度必填，报表
 * 双维度聚合。词表与列注释同源，值域冻结。
 */
public enum SeverityGrade {

    /** A 级：无害 */
    GRADE_A("A"),

    /** B 级：轻度（需观察/简单处置） */
    GRADE_B("B"),

    /** C 级：中度（需治疗/延长住院） */
    GRADE_C("C"),

    /** D 级：重度（永久性损害/危及生命） */
    GRADE_D("D"),

    /** E 级：死亡 */
    GRADE_E("E");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    SeverityGrade(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（A~E），非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧（上报表单显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static SeverityGrade fromCode(String code) {
        for (SeverityGrade value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

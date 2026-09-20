package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 就诊类型枚举（outpatient.visit.visit_type 列值域，M03 Spec §4 六类词表）：P1 挂号链路按号别
 * 派生（EMERGENCY 号别→EMERGENCY，其余号别→GENERAL；REVISIT 号别另经 is_revisit 列表达），
 * INTERNET/MDT/SPECIAL/OTHER 为词表预留位。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum VisitType {

    /** 急诊（急诊分级Ⅰ~Ⅳ经 triage_level 表达） */
    EMERGENCY("EMERGENCY"),

    /** 普通（普通门诊，P1 挂号链路默认值） */
    GENERAL("GENERAL"),

    /** 专科（词表预留位） */
    SPECIAL("SPECIAL"),

    /** 互联网诊疗（M18 线上就诊，词表预留位） */
    INTERNET("INTERNET"),

    /** 多学科诊疗（词表预留位） */
    MDT("MDT"),

    /** 其他（词表兜底位） */
    OTHER("OTHER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    VisitType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 GENERAL），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空（列 NOT NULL）
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static VisitType fromCode(String code) {
        for (VisitType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的就诊类型 code: " + code);
    }
}

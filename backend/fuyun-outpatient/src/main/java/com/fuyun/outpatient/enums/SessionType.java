package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 门诊时段枚举（outpatient.schedule_template.session 与 outpatient.schedule.session 列值域，
 * M03 FU-M03-01）：一个排班/池行颗粒度=日期×时段，晚间门诊（EVENING）为延时服务预留词表位。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum SessionType {

    /** 上午（MORNING） */
    MORNING("MORNING"),

    /** 下午（AFTERNOON） */
    AFTERNOON("AFTERNOON"),

    /** 晚间（EVENING，延时门诊预留） */
    EVENING("EVENING");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    SessionType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 MORNING），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或外部入参；非空（列 NOT NULL）
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或词表外入参），建议上游按参数校验处置
     */
    public static SessionType fromCode(String code) {
        for (SessionType session : values()) {
            if (session.code.equals(code)) {
                return session;
            }
        }
        throw new IllegalArgumentException("未知的门诊时段 code: " + code);
    }
}

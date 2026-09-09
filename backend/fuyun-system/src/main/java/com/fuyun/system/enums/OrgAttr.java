package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 机构属性枚举（system.sys_org.org_attr 列值域，M01 Spec §4）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum OrgAttr {

    /** 临床 */
    CLINICAL("CLINICAL"),

    /** 医技 */
    MEDTECH("MEDTECH"),

    /** 职能 */
    ADMIN("ADMIN");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    OrgAttr(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CLINICAL），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或版本不一致），建议上游按数据异常处置
     */
    public static OrgAttr fromCode(String code) {
        for (OrgAttr attr : values()) {
            if (attr.code.equals(code)) {
                return attr;
            }
        }
        throw new IllegalArgumentException("未知的机构属性 code: " + code);
    }
}

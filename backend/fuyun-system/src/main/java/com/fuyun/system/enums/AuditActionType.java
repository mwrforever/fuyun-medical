package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审计动作类型枚举（system.audit_log.action_type 列值域，M01 Spec §4）。
 *
 * <p>P0 注解落点：LOGIN（登录/登出）与 WRITE（字典写端点）；SENSITIVE_QUERY 留痕随 P1。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum AuditActionType {

    /** 登录/登出 */
    LOGIN("LOGIN"),

    /** 业务写操作（新增/修改/删除/发布） */
    WRITE("WRITE"),

    /** 打印 */
    PRINT("PRINT"),

    /** 敏感查询留痕（P1 随查询审计交付） */
    SENSITIVE_QUERY("SENSITIVE_QUERY");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    AuditActionType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 LOGIN），非空；MP 写列与 JSON 序列化均取本值
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
    public static AuditActionType fromCode(String code) {
        for (AuditActionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的审计动作类型 code: " + code);
    }
}

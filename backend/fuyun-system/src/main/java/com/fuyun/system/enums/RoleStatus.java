package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 角色启停状态枚举（system.sys_role.status 列值域，M01 Spec §4）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum RoleStatus {

    /** 启用：角色可被授予用户且授权生效 */
    ACTIVE("ACTIVE"),

    /** 停用：禁配新用户（存量用户会话内角色摘要不回溯撤销，重新登录后失效） */
    DISABLED("DISABLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    RoleStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ACTIVE），非空；MP 写列与 JSON 序列化均取本值
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
    public static RoleStatus fromCode(String code) {
        for (RoleStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的角色状态 code: " + code);
    }
}

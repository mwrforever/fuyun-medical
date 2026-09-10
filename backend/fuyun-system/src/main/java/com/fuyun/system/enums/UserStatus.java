package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户账号状态枚举（system.sys_user.status 列值域，M01 Spec §5 用户状态机）。
 *
 * <p>状态机：ACTIVE 正常 ↔ LOCKED 锁定（连续失败达阈值，到期自动恢复）→ DISABLED 停用（终态，可人工复启）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum UserStatus {

    /** 正常：可登录 */
    ACTIVE("ACTIVE"),

    /** 锁定：连续登录失败达阈值，锁定到期自动恢复（P0 锁定经 locked_until 表达，本值供状态机语义完整） */
    LOCKED("LOCKED"),

    /** 停用：禁止登录（403 SYS-1006） */
    DISABLED("DISABLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    UserStatus(String code) {
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
    public static UserStatus fromCode(String code) {
        for (UserStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的用户状态 code: " + code);
    }
}

package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执业授权状态枚举（system.practice_grant.status 列值域，M01 FU-M01-04 状态机）。
 *
 * <p>状态机：EFFECTIVE 生效（登记初始态）→ SUSPENDED 停权（withdraw CAS 终态，可重新登记）；
 * EXPIRED 过期为读侧派生态（check/清单按 valid_to 与校验时点比较派生，无定时任务回写——
 * PrivacyAuthServiceImpl.deriveStatus 同款先例）。枚举规范（backend 宪法 A.2-7）：code 字段 +
 * {@code @EnumValue}（MP DB 列映射）+ {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum PracticeGrantStatus {

    /** 生效：授权在有效期内可用（部分唯一索引 uk_practice_grant_active 仅约束本态） */
    EFFECTIVE("EFFECTIVE"),

    /** 停权：withdraw 落库终态，不占生效唯一性，可重新登记 */
    SUSPENDED("SUSPENDED"),

    /** 过期：读侧派生态，库内不落（valid_to 已过的 EFFECTIVE 行在读取时派生展示） */
    EXPIRED("EXPIRED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PracticeGrantStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 EFFECTIVE），非空；MP 写列与 JSON 序列化均取本值
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
    public static PracticeGrantStatus fromCode(String code) {
        for (PracticeGrantStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的执业授权状态 code: " + code);
    }
}

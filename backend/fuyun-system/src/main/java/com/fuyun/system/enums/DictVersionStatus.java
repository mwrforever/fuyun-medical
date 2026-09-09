package com.fuyun.system.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 字典版本状态枚举（system.dict_version.status 列值域，M01 Spec §5 版本状态机）。
 *
 * <p>状态机：DRAFT → PUBLISHED（发布，同 type 旧 PUBLISHED 随即置 DEPRECATED）→ DEPRECATED（终态）；
 * 仅 DRAFT 可编辑条目，仅 DRAFT 可发布。发布即生效（P0，effective_at=now），fy.delay 定时生效随 P1。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射；禁止常量类/整型模拟枚举。
 */
public enum DictVersionStatus {

    /** 草稿：可编辑条目、可发布 */
    DRAFT("DRAFT"),

    /** 已发布：对外生效（同一 type 同一时刻仅一个） */
    PUBLISHED("PUBLISHED"),

    /** 已废弃：被更新版本替代的终态 */
    DEPRECATED("DEPRECATED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DictVersionStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PUBLISHED），非空；MP 写列与 JSON 序列化均取本值
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
    public static DictVersionStatus fromCode(String code) {
        for (DictVersionStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的字典版本状态 code: " + code);
    }
}

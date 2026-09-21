package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 号源池行状态枚举（outpatient.appt_number_pool.status 列值域，M03 Spec §5）：ACTIVE 可约（放号
 * 生成初始态，余量查询与扣减谓词仅认本态）/ STOPPED 停用（停诊整池联动，恢复时批量迁回）/
 * EXPIRED 过期（sched_date 已过的对账归档态，随对账任务流转，P1 读侧判据）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum PoolStatus {

    /** 可约：余量查询（ACTIVE 且 used_count&lt;total_quota）与扣减 CAS 的准入态 */
    ACTIVE("ACTIVE"),

    /** 停用：停诊整池联动态，恢复排班批量迁回 ACTIVE */
    STOPPED("STOPPED"),

    /** 过期：sched_date 已过的对账归档态 */
    EXPIRED("EXPIRED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PoolStatus(String code) {
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或词表外入参），建议上游按数据异常处置
     */
    public static PoolStatus fromCode(String code) {
        for (PoolStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的号源池状态 code: " + code);
    }
}

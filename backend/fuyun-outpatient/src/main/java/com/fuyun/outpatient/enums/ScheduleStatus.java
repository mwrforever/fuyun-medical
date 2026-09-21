package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 排班日历状态枚举（outpatient.schedule.status 列值域，M03 Spec §5）：NORMAL 正常（放号生成初始态）/
 * STOPPED 停诊（stop CAS 迁移目标态，整池行联动 STOPPED 并发布 schedule.stopped；resume 仅在
 * sched_date 未过期时可迁回 NORMAL——过期排班不可恢复，OP-1004 判定）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum ScheduleStatus {

    /** 正常：放号生成初始态，池行可约 */
    NORMAL("NORMAL"),

    /** 停诊：整池作废态，已约患者改期/退费联动依据（schedule.stopped 载荷） */
    STOPPED("STOPPED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ScheduleStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 NORMAL），非空；MP 写列与 JSON 序列化均取本值
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
    public static ScheduleStatus fromCode(String code) {
        for (ScheduleStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的排班状态 code: " + code);
    }
}

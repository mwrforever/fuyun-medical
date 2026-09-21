package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 预约单状态枚举（outpatient.appointment.status 列值域，M03 Spec §5）：RESERVED 占位（portal 支付
 * 时限内）→ TAKEN（取号/当日挂号，签发 visit）；RESERVED→CANCELLED（退号）/→NO_SHOW（支付超时爽约）；
 * TAKEN→CANCELLED（当日退号，退费联动随 Task 6）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum ApptStatus {

    /** 已预约占位（portal 支付时限内；窗口/自助一步直达 TAKEN 不停留本态） */
    RESERVED("RESERVED"),

    /** 已取号/已挂号（visit 已签发并回填 visit_id） */
    TAKEN("TAKEN"),

    /** 已退号（终态；线上退号与退费联动随 Task 6） */
    CANCELLED("CANCELLED"),

    /** 爽约（终态；支付时限超时经延迟任务置入，号源回池+信用记录） */
    NO_SHOW("NO_SHOW");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ApptStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 RESERVED），非空；MP 写列与 JSON 序列化均取本值
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
    public static ApptStatus fromCode(String code) {
        for (ApptStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的预约单状态 code: " + code);
    }
}

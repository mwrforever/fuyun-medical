package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 挂号费状态枚举（outpatient.appointment.fee_status 列值域）：仅表达结算状态流转位，零金额字段
 * （资金无涉红线裁决 7——挂号费/退费金额一律 M13 权威，本列供退号退费联动判定与对账展示）。
 * 缴费回填（PAID+fee_settlement_id）与退费终态（REFUNDED）随计费联动任务交付。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum FeeStatusType {

    /** 未缴（挂号落库初始态；portal 占位时限内未支付） */
    UNPAID("UNPAID"),

    /** 已缴（结算回填，随挂号费计费联动交付） */
    PAID("PAID"),

    /** 已退（退号退费终态，随 Task 6 退费联动交付） */
    REFUNDED("REFUNDED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    FeeStatusType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 UNPAID），非空；MP 写列与 JSON 序列化均取本值
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
    public static FeeStatusType fromCode(String code) {
        for (FeeStatusType status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的挂号费状态 code: " + code);
    }
}

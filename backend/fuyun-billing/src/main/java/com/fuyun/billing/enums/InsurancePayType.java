package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医保支付属性（insurance_mapping.insurance_pay_type 列值域，FU-M13-01 贯标）：
 * 甲/乙/丙分类驱动先自付比例与限价拆分，目录外归 SELF_EXPENSE。枚举规范同 FeeStatus。
 */
public enum InsurancePayType {

    /** 甲类（全额纳入医保支付） */
    CLASS_A("CLASS_A"),

    /** 乙类（先自付比例后纳入） */
    CLASS_B("CLASS_B"),

    /** 丙类（医保不予支付） */
    CLASS_C("CLASS_C"),

    /** 自费（目录外项目） */
    SELF_EXPENSE("SELF_EXPENSE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    InsurancePayType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CLASS_A），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static InsurancePayType fromCode(String code) {
        for (InsurancePayType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的医保支付属性 code: " + code);
    }
}

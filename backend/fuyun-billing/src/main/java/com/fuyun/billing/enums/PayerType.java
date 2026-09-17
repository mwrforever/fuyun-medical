package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 支付类型（settlement.payer_type 列值域，Spec §4）：费用承担方五分类，
 * 医保三型参与贯标拆分（统筹/个账/自付），商保 COMM_INS 预留。枚举规范同 FeeStatus。
 */
public enum PayerType {

    /** 自费 */
    SELF_PAY("SELF_PAY"),

    /** 市医保（城镇职工/居民） */
    CITY_INS("CITY_INS"),

    /** 省医保 */
    PROV_INS("PROV_INS"),

    /** 异地医保（就医地目录） */
    OUTSIDE_INS("OUTSIDE_INS"),

    /** 商业保险（预留） */
    COMM_INS("COMM_INS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PayerType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 SELF_PAY），非空；MP 写列与 JSON 序列化均取本值
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
    public static PayerType fromCode(String code) {
        for (PayerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的支付类型 code: " + code);
    }
}

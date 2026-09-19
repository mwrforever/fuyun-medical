package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 国家医保支付属性四值（drug.nhsa_pay_type 列值域，Spec :105）：目录分甲/乙/丙类与自费；
 * 未对照药品本列为 NULL。
 */
public enum NhsaPayType {

    /** 甲类（全额纳入） */
    JIA("JIA"),

    /** 乙类（先自付比例） */
    YI("YI"),

    /** 丙类 */
    BING("BING"),

    /** 自费 */
    SELF("SELF");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    NhsaPayType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举（脏数据）
     */
    public static NhsaPayType fromCode(String code) {
        for (NhsaPayType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的医保支付属性 code: " + code);
    }
}

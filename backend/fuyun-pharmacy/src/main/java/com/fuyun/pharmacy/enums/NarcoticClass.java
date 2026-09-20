package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 毒麻类别五值（drug.narcotic_class 列值域，Spec :105）：普通/麻醉/一类精神/二类精神/毒性；
 * 非 NORMAL 的处方类别与五专管理随 P3（FU-M06-06）。
 */
public enum NarcoticClass {

    /** 普通药品 */
    NORMAL("NORMAL"),

    /** 麻醉药品 */
    NARCOTIC("NARCOTIC"),

    /** 一类精神药品 */
    PSYCHOTIC_I("PSYCHOTIC_I"),

    /** 二类精神药品 */
    PSYCHOTIC_II("PSYCHOTIC_II"),

    /** 医疗用毒性药品 */
    TOXIC("TOXIC");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    NarcoticClass(String code) {
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
    public static NarcoticClass fromCode(String code) {
        for (NarcoticClass value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的毒麻类别 code: " + code);
    }
}

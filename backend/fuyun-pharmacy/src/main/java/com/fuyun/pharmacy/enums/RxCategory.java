package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 处方类别五值（prescription.rx_category 列值域，Spec :106）：随处方内最高毒麻级别药品派生。
 */
public enum RxCategory {

    /** 普通 */
    NORMAL("NORMAL"),

    /** 麻醉（红处方） */
    NARCOTIC("NARCOTIC"),

    /** 一类精神 */
    PSYCHOTIC_I("PSYCHOTIC_I"),

    /** 二类精神 */
    PSYCHOTIC_II("PSYCHOTIC_II"),

    /** 毒性 */
    TOXIC("TOXIC");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RxCategory(String code) {
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
    public static RxCategory fromCode(String code) {
        for (RxCategory value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的处方类别 code: " + code);
    }
}

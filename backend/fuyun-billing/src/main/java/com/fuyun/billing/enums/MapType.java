package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医保对照类型（insurance_mapping.map_type 列值域，FU-M13-01 贯标）：药品/耗材对照引用
 * M06 国家码，本表仅登记映射关系。枚举规范同 FeeStatus。
 */
public enum MapType {

    /** 诊疗项目对照 */
    TREATMENT("TREATMENT"),

    /** 药品对照 */
    DRUG("DRUG"),

    /** 耗材对照 */
    CONSUMABLE("CONSUMABLE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MapType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 TREATMENT），非空；MP 写列与 JSON 序列化均取本值
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
    public static MapType fromCode(String code) {
        for (MapType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的医保对照类型 code: " + code);
    }
}

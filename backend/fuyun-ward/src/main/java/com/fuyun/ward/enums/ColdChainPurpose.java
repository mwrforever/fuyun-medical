package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 冷链用途枚举（cold_chain_archive.purpose 四值词表，V1101 列注释冻结）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum ColdChainPurpose {

    /** 疫苗 */
    VACCINE("VACCINE"),

    /** 血液 */
    BLOOD("BLOOD"),

    /** 试剂 */
    REAGENT("REAGENT"),

    /** 药品 */
    PHARMA("PHARMA");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    ColdChainPurpose(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 VACCINE），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或请求体文本；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法请求值），
     *                                  建议调用方按校验失败/数据异常处置
     */
    public static ColdChainPurpose fromCode(String code) {
        for (ColdChainPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("未知的冷链用途 code: " + code);
    }
}

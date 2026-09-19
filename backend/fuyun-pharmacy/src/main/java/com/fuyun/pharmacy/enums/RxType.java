package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 处方类型四值（prescription.rx_type 列值域，Spec :106）：DISCHARGE/INTERNET 为预留值，PR-4 开方拒收。
 */
public enum RxType {

    /** 门诊 */
    OUTPATIENT("OUTPATIENT"),

    /** 急诊 */
    EMERGENCY("EMERGENCY"),

    /** 出院带药（P2 预留） */
    DISCHARGE("DISCHARGE"),

    /** 互联网处方（P2 预留） */
    INTERNET("INTERNET");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RxType(String code) {
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
    public static RxType fromCode(String code) {
        for (RxType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的处方类型 code: " + code);
    }
}

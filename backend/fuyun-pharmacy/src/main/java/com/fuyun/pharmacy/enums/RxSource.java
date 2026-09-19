package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 处方来源二值（prescription.rx_source 列值域）：DISCHARGE_CONVERT 随出院带药（P2），PR-4 拒收。
 */
public enum RxSource {

    /** 医生站 */
    DOCTOR_STATION("DOCTOR_STATION"),

    /** 出院带药转换（P2 预留） */
    DISCHARGE_CONVERT("DISCHARGE_CONVERT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RxSource(String code) {
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
    public static RxSource fromCode(String code) {
        for (RxSource value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的处方来源 code: " + code);
    }
}

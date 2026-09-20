package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 药品状态二值（drug.status 列值域）：ENABLED 可开方/DISABLED 停用（停用拒开方）。
 */
public enum DrugStatus {

    /** 启用 */
    ENABLED("ENABLED"),

    /** 停用（保留历史引用，禁新开方） */
    DISABLED("DISABLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    DrugStatus(String code) {
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
    public static DrugStatus fromCode(String code) {
        for (DrugStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的药品状态 code: " + code);
    }
}

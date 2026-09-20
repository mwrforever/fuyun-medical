package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 批次状态四值（Spec :113）：在库/冻结/待销毁/清零；配药锁定以 locked_qty 表达不改状态。
 */
public enum BatchStatus {

    /** 在库 */
    IN_STOCK("IN_STOCK"),

    /** 冻结（效期/差异联动，P3） */
    FROZEN("FROZEN"),

    /** 待销毁 */
    PENDING_DESTROY("PENDING_DESTROY"),

    /** 清零 */
    EXHAUSTED("EXHAUSTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    BatchStatus(String code) {
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
    public static BatchStatus fromCode(String code) {
        for (BatchStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的批次状态 code: " + code);
    }
}

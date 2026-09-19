package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 库存流水动作（批次账最小实现二值，主控裁决 2；采购/调拨/盘点动作随 P3 药房管理扩展）。
 */
public enum LedgerAction {

    /** 发药出库（负数） */
    ISSUE("ISSUE"),

    /** 退药回补（正数） */
    RETURN_RESTOCK("RETURN_RESTOCK");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    LedgerAction(String code) {
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
    public static LedgerAction fromCode(String code) {
        for (LedgerAction value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的库存流水动作 code: " + code);
    }
}

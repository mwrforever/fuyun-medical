package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 押金流水类型（deposit_txn.txn_type 列值域，Spec §4）：金额恒正、方向由本类型表达
 * （DEPOSIT 缴入增余额，REFUND/OFFSET 减余额）。枚举规范同 FeeStatus。
 */
public enum DepositTxnType {

    /** 缴入（余额增加） */
    DEPOSIT("DEPOSIT"),

    /** 退回（生成对冲行，余额减少） */
    REFUND("REFUND"),

    /** 结算抵扣（出院结算冲抵，余额减少） */
    OFFSET("OFFSET");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DepositTxnType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 DEPOSIT），非空；MP 写列与 JSON 序列化均取本值
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
    public static DepositTxnType fromCode(String code) {
        for (DepositTxnType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的押金流水类型 code: " + code);
    }
}

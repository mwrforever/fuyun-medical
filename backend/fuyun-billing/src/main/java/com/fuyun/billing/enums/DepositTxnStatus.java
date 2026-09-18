package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 押金流水状态（deposit_txn.status 列值域，Spec §5）：ACTIVE 行禁改（资金留痕只增），
 * 退回/抵扣后标记终态 REFUNDED/OFFSET。枚举规范同 FeeStatus。
 */
public enum DepositTxnStatus {

    /** 有效（原始流水，禁修改） */
    ACTIVE("ACTIVE"),

    /** 已退回（原缴入行被退回对冲后标记） */
    REFUNDED("REFUNDED"),

    /** 已抵扣（被结算 OFFSET 冲抵后标记） */
    OFFSET("OFFSET");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DepositTxnStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ACTIVE），非空；MP 写列与 JSON 序列化均取本值
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
    public static DepositTxnStatus fromCode(String code) {
        for (DepositTxnStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的押金流水状态 code: " + code);
    }
}

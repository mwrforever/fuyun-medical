package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结算单状态机（Spec §5）：DRAFT→PRESETTLED→SETTLED→REFUNDED（退费联动）；
 * SETTLED 红冲撤销归 RED_REVERSED；未结算单可 CANCELLED 作废。枚举规范同 FeeStatus。
 */
public enum SettlementStatus {

    /** 结算草稿（未收银） */
    DRAFT("DRAFT"),

    /** 预结算完成（医保回执已回，待收银确认） */
    PRESETTLED("PRESETTLED"),

    /** 已正式结算（只读基点，红线 2） */
    SETTLED("SETTLED"),

    /** 已退费（退费执行后回写） */
    REFUNDED("REFUNDED"),

    /** 红冲已撤销（原结算单冲正后标记） */
    RED_REVERSED("RED_REVERSED"),

    /** 已作废（未结算草稿作废） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    SettlementStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 DRAFT），非空；MP 写列与 JSON 序列化均取本值
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
    public static SettlementStatus fromCode(String code) {
        for (SettlementStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的结算状态 code: " + code);
    }
}

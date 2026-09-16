package com.fuyun.patient.api;

/**
 * 一卡通记账类型契约（M02 Spec §4 card_txn.txn_type；金额恒为正数，方向由类型表达）。
 * 枚举名与库内 VARCHAR 取值一致（MyBatis 默认 EnumTypeHandler 按 name 存取）。
 */
public enum CardTxnType {

    /** 充值（余额 +） */
    RECHARGE,

    /** 消费（余额 −；余额不足拒绝 PAT-1016） */
    PAY,

    /** 退款（余额 +） */
    REFUND,

    /** 冲正（余额 −；余额不足拒绝 PAT-1016） */
    REVERSE;

    /**
     * 判定该类型是否为余额增加方向。
     *
     * @return true=入账（RECHARGE/REFUND）；false=出账（PAY/REVERSE）
     */
    public boolean isCredit() {
        return this == RECHARGE || this == REFUND;
    }
}

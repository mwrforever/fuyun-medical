package com.fuyun.patient.enums;

/**
 * 一卡通账户状态机：ACTIVE ⇄ FROZEN（挂失联动）；ACTIVE/FROZEN→CLOSED（销户，余额必须为零）。
 */
public enum CardAccountStatus {
    /** 正常 */
    ACTIVE,
    /** 冻结（挂失联动） */
    FROZEN,
    /** 已销户（销户前置校验余额必须为零） */
    CLOSED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：card_account.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static CardAccountStatus of(String code) {
        return valueOf(code);
    }
}

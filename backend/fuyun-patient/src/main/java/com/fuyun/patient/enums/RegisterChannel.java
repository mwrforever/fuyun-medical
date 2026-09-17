package com.fuyun.patient.enums;

/**
 * 建档渠道词表（FU-M02-01：窗口读卡/自助机/线上实人绑卡/住院登记/急诊）。
 */
public enum RegisterChannel {
    /** 窗口（读卡/人工录入） */
    WINDOW,
    /** 自助机 */
    SELF_SERVICE,
    /** 线上（实人绑卡） */
    ONLINE,
    /** 住院登记 */
    INPATIENT_REGISTER,
    /** 急诊 */
    EMERGENCY;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 词表值，非空；来源：patient.register_channel 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static RegisterChannel of(String code) {
        return valueOf(code);
    }
}

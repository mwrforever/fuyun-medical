package com.fuyun.patient.enums;

/**
 * 标识状态机（M02 §5）：ACTIVE ⇄ LOST（挂失解析立即失效）、LOST→REPLACED（补卡转移终态）、
 * ACTIVE→DISABLED（解绑/注销）。
 */
public enum IdentifierStatus {
    /** 有效 */
    ACTIVE,
    /** 已挂失（解析立即失效） */
    LOST,
    /** 已补换（补卡转移后的原卡终态） */
    REPLACED,
    /** 已停用（解绑/注销） */
    DISABLED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：patient_identifier.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static IdentifierStatus of(String code) {
        return valueOf(code);
    }
}

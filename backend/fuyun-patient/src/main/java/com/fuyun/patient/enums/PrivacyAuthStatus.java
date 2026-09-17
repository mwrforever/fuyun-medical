package com.fuyun.patient.enums;

/**
 * 隐私授权状态机：EFFECTIVE→EXPIRED（到期自动，读侧按 valid_to 派生）/REVOKED（撤回）。
 */
public enum PrivacyAuthStatus {
    /** 有效 */
    EFFECTIVE,
    /** 已到期（读侧按 valid_to 派生） */
    EXPIRED,
    /** 已撤回 */
    REVOKED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：privacy_auth.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static PrivacyAuthStatus of(String code) {
        return valueOf(code);
    }
}

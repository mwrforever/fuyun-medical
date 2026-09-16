package com.fuyun.patient.enums;

/**
 * 隐私授权类型（个保法单独同意载体）：建档知情同意/敏感信息使用单独同意/监护人代管授权。
 */
public enum PrivacyAuthType {
    /** 建档知情同意（FU-M02-01 建档强制采集） */
    INFORMED_CONSENT,
    /** 敏感信息使用单独同意 */
    SENSITIVE_USE,
    /** 监护人代管授权 */
    GUARDIAN;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 词表值，非空；来源：privacy_auth.auth_type 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static PrivacyAuthType of(String code) {
        return valueOf(code);
    }
}

package com.fuyun.patient.enums;

/**
 * 疑似重复状态机：PENDING→MERGED（按合并处理）/EXCLUDED（排除必填理由）；
 * PENDING 超时提醒归 M01 通知链路。
 */
public enum DuplicateStatus {
    /** 待人工审核 */
    PENDING,
    /** 已按合并处理 */
    MERGED,
    /** 已排除（必填理由留痕） */
    EXCLUDED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：possible_duplicate.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static DuplicateStatus of(String code) {
        return valueOf(code);
    }
}

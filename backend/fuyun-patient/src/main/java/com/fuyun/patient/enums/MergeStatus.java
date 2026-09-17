package com.fuyun.patient.enums;

/**
 * 合并记录状态机：PROCESSING→COMPLETED（广播成功后）/FAILED（前置失败或广播异常，可重试回
 * PROCESSING）；COMPLETED→REVERSED（拆分，终态）。
 */
public enum MergeStatus {
    /** 合并进行中 */
    PROCESSING,
    /** 合并完成（广播成功后） */
    COMPLETED,
    /** 合并失败（前置失败或广播异常，可重试回 PROCESSING） */
    FAILED,
    /** 已拆分恢复（终态） */
    REVERSED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：merge_record.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static MergeStatus of(String code) {
        return valueOf(code);
    }
}

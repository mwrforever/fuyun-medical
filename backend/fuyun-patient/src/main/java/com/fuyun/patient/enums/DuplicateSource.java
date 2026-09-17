package com.fuyun.patient.enums;

/**
 * 疑似重复来源：建档实时检测 / 周期批量扫描（增量比对）。
 */
public enum DuplicateSource {
    /** 建档实时检测（FU-M02-02 建档即触发） */
    REGISTER_SCAN,
    /** 周期批量扫描（增量比对） */
    BATCH_SCAN;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内来源值，非空；来源：possible_duplicate.source 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static DuplicateSource of(String code) {
        return valueOf(code);
    }
}

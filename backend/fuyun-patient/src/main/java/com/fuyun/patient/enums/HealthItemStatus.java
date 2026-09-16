package com.fuyun.patient.enums;

/**
 * 健康档案项状态：纠错不改原记录——原行置 CORRECTED 并新增行（全程留痕）。
 */
public enum HealthItemStatus {
    /** 有效 */
    ACTIVE,
    /** 已纠错（原行留痕，由新行替代） */
    CORRECTED;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 库内状态值，非空；来源：patient_health_item.status 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static HealthItemStatus of(String code) {
        return valueOf(code);
    }
}

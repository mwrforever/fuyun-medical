package com.fuyun.patient.enums;

/**
 * 健康档案项类型（FU-M02-05）：过敏史/慢病史/手术史/免疫接种史（对齐区域平台基本健康信息口径）。
 */
public enum HealthItemType {
    /** 过敏史 */
    ALLERGY,
    /** 慢病史 */
    CHRONIC,
    /** 手术史 */
    SURGERY,
    /** 免疫接种史 */
    VACCINATION;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 词表值，非空；来源：patient_health_item.item_type 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static HealthItemType of(String code) {
        return valueOf(code);
    }
}

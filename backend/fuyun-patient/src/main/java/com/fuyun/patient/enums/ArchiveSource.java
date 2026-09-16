package com.fuyun.patient.enums;

/**
 * 档案来源：正式 / 急诊无名氏临时档 / 新生儿临时档（可关联母亲档案，取得身份后转正式）。
 */
public enum ArchiveSource {
    /** 正式档案 */
    STANDARD,
    /** 急诊无名氏临时档 */
    TEMP_ANONYMOUS,
    /** 新生儿临时档（可关联母亲档案，取得身份后转正式） */
    TEMP_NEWBORN;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 词表值，非空；来源：patient.archive_source 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static ArchiveSource of(String code) {
        return valueOf(code);
    }
}

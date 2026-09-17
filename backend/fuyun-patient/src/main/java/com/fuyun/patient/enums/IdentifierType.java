package com.fuyun.patient.enums;

/**
 * 标识类型词表（M02 §4 patient_identifier）：身份证/护照/军官证/其他法定证件/医保电子凭证/电子健康卡/
 * 就诊卡/院内病历号；卡类为 HEALTH_CARD/VISIT_CARD（card_no 必填）。
 */
public enum IdentifierType {
    /** 身份证（EMPI 强标识，盲索引等值查主路径） */
    ID_CARD,
    /** 护照（法定证件强标识） */
    PASSPORT,
    /** 军官证（法定证件强标识） */
    MILITARY_OFFICER,
    /** 其他法定证件 */
    OTHER_LEGAL,
    /** 医保电子凭证（经 M13 通道核验） */
    INSURANCE_ELECTRONIC,
    /** 电子健康卡（卡类介质，card_no 必填） */
    HEALTH_CARD,
    /** 就诊卡（卡类介质，card_no 必填） */
    VISIT_CARD,
    /** 院内病历号 */
    MEDICAL_RECORD_NO;

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 词表值，非空；来源：patient_identifier.identifier_type 列
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static IdentifierType of(String code) {
        return valueOf(code);
    }
}

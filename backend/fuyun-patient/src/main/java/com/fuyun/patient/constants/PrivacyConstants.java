package com.fuyun.patient.constants;

/**
 * 隐私脱敏常量（privacy_mask_rule 词表）：target_field 落库词与 mask_pattern 保留策略枚举值。
 * 规则行为与 SensitiveMasker 组合关系见 PrivacyMaskServiceImpl（各端展示统一生效，M02 FU-M02-06）。
 */
public final class PrivacyConstants {

    /** 目标字段：姓名 */
    public static final String TARGET_NAME = "name";
    /** 目标字段：证件号 */
    public static final String TARGET_ID_CARD_NO = "idCardNo";
    /** 目标字段：手机号 */
    public static final String TARGET_MOBILE = "mobile";
    /** 目标字段：住址 */
    public static final String TARGET_ADDRESS = "address";
    /** 目标字段：出生日期 */
    public static final String TARGET_BIRTH_DATE = "birthDate";

    /** 保留策略：姓名保留姓氏（SensitiveMasker.maskName） */
    public static final String PATTERN_KEEP_FIRST = "KEEP_FIRST";
    /** 保留策略：证件号保留前 6 后 4（maskIdCard） */
    public static final String PATTERN_KEEP_6_4 = "KEEP_6_4";
    /** 保留策略：手机号保留前 3 后 4（maskPhone） */
    public static final String PATTERN_KEEP_3_4 = "KEEP_3_4";
    /** 保留策略：住址保留省/市前缀 */
    public static final String PATTERN_KEEP_PROVINCE_CITY = "KEEP_PROVINCE_CITY";
    /** 保留策略：出生日期保留年份 */
    public static final String PATTERN_KEEP_YEAR = "KEEP_YEAR";

    /** 私有构造器：常量类禁止实例化（backend 宪法 A.2-6） */
    private PrivacyConstants() {}
}

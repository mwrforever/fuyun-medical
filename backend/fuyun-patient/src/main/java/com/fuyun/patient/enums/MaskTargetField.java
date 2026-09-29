package com.fuyun.patient.enums;

import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.PatientErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 脱敏目标字段词表（privacy_mask_rule.target_field 落库值为小驼峰 name/idCardNo/mobile/address/birthDate，
 * 与 VO 属性名一致；枚举提供 {@link #column()} 返回落库词）。
 */
public enum MaskTargetField {
    /** 姓名 */
    NAME("name"),
    /** 证件号 */
    ID_CARD_NO("idCardNo"),
    /** 手机号 */
    MOBILE("mobile"),
    /** 住址 */
    ADDRESS("address"),
    /** 出生日期 */
    BIRTH_DATE("birthDate");

    /** privacy_mask_rule.target_field 落库词（小驼峰，与 VO 属性名一致） */
    private final String column;

    MaskTargetField(String column) {
        this.column = column;
    }

    /**
     * 取落库词表值。
     *
     * @return privacy_mask_rule.target_field 落库词（小驼峰）
     */
    public String column() {
        return column;
    }

    /**
     * 落库词反查枚举（明文查阅穷举分派与词表收口点）。
     *
     * @param word privacy_mask_rule.target_field 落库词，非空；来源：脱敏规则行或明文查阅请求字段词
     * @return 对应枚举；未知词显式暴露（脏数据告警），不静默归入默认脱敏
     * @throws BizException PAT-1025（400，词表外落库词——请求字段词/规则表存储值漂移显式拒）；
     *                      EX-19 收口 A 类：词表外经 BizException 出 400 ProblemDetail 携带 errorCode，
     *                      不再以裸 IAE 走全局 500 通道
     */
    public static MaskTargetField ofColumn(String word) {
        for (MaskTargetField field : values()) {
            // 逐词精确匹配落库词表（种子固定 5 条，见 V103）
            if (field.column.equals(word)) {
                return field;
            }
        }
        throw new BizException(PatientErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知脱敏目标字段落库词: " + word);
    }

    /**
     * code↔enum 双向映射（A.2-7；code 与枚举名一致，MyBatis 默认按 name 存取）。
     *
     * @param code 枚举名，非空；来源：应用层引用
     * @return 对应枚举；未知值由 valueOf 抛 IllegalArgumentException（脏数据显式暴露）
     */
    public static MaskTargetField of(String code) {
        return valueOf(code);
    }
}

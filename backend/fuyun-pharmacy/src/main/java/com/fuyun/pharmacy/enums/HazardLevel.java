package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 高警示（高危）药品等级四值（drug.hazard_level 列值域，Spec :105）：A/B/C 三级管理（调研依据 12），
 * A 级专用贮存双人复核；NONE=非高警示。
 */
public enum HazardLevel {

    /** 非高警示 */
    NONE("NONE"),

    /** A 级（专用药柜/专区、专用袋、双人复核） */
    A("A"),

    /** B 级 */
    B("B"),

    /** C 级 */
    C("C");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    HazardLevel(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空
     * @throws BizException PH-1022（400）code 无对应枚举（值域外 code 显式拒绝——EX-19 BE-C3-05 A 类收口）
     */
    public static HazardLevel fromCode(String code) {
        for (HazardLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(PharmacyErrorCode.ENUM_CODE_MALFORMED, HttpStatus.BAD_REQUEST, "未知的高警示等级 code: " + code);
    }
}

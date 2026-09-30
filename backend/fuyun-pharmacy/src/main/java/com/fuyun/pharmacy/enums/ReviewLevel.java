package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 审方预检分级三值（prescription.review_level 列值域，Spec :132）：PR-4 预检恒 PASS（占位级），WARNING/REJECT 随 P3 引擎。
 */
public enum ReviewLevel {

    /** 通过级（PR-4 恒定值） */
    PASS("PASS"),

    /** 提示级（待药师复审，P3） */
    WARNING("WARNING"),

    /** 硬拦截级（驳回，P3） */
    REJECT("REJECT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ReviewLevel(String code) {
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
    public static ReviewLevel fromCode(String code) {
        for (ReviewLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(
                PharmacyErrorCode.ENUM_CODE_MALFORMED, HttpStatus.BAD_REQUEST, "未知的审方预检分级 code: " + code);
    }
}

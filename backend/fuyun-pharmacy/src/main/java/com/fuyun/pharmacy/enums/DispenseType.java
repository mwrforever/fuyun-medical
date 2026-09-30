package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 调剂单类型（Spec :111）：PR-4 仅门诊发药；住院摆药/PIVAS 等值随 P2 增补（枚举值域扩展属契约演进）。
 */
public enum DispenseType {

    /** 门诊发药 */
    OUTPATIENT("OUTPATIENT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    DispenseType(String code) {
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
    public static DispenseType fromCode(String code) {
        for (DispenseType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(PharmacyErrorCode.ENUM_CODE_MALFORMED, HttpStatus.BAD_REQUEST, "未知的调剂单类型 code: " + code);
    }
}

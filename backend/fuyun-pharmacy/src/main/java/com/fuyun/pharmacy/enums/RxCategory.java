package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 处方类别五值（prescription.rx_category 列值域，Spec :106）：随处方内最高毒麻级别药品派生。
 */
public enum RxCategory {

    /** 普通 */
    NORMAL("NORMAL"),

    /** 麻醉（红处方） */
    NARCOTIC("NARCOTIC"),

    /** 一类精神 */
    PSYCHOTIC_I("PSYCHOTIC_I"),

    /** 二类精神 */
    PSYCHOTIC_II("PSYCHOTIC_II"),

    /** 毒性 */
    TOXIC("TOXIC");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RxCategory(String code) {
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
    public static RxCategory fromCode(String code) {
        for (RxCategory value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new BizException(PharmacyErrorCode.ENUM_CODE_MALFORMED, HttpStatus.BAD_REQUEST, "未知的处方类别 code: " + code);
    }
}

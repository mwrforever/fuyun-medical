package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 医保对照类型（insurance_mapping.map_type 列值域，FU-M13-01 贯标）：药品/耗材对照引用
 * M06 国家码，本表仅登记映射关系。枚举规范同 FeeStatus。
 */
public enum MapType {

    /** 诊疗项目对照 */
    TREATMENT("TREATMENT"),

    /** 药品对照 */
    DRUG("DRUG"),

    /** 耗材对照 */
    CONSUMABLE("CONSUMABLE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MapType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 TREATMENT），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws BizException BILL-1034（400）：code 无对应枚举常量（外部输入/存储值词表外，
     *      显式拒禁静默兜底——BE-C3-05 收口，改抛前为裸 IllegalArgumentException 出 500）
     */
    public static MapType fromCode(String code) {
        for (MapType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的医保对照类型 code: " + code);
    }
}

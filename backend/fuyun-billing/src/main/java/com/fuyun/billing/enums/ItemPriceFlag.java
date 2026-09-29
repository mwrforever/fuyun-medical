package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 收费标记（charge_item.price_flag 列值域，M13 Spec §4）：约束项目是否允许脱离组合单独计价。枚举规范同 FeeStatus。
 */
public enum ItemPriceFlag {

    /** 可单独收费 */
    SINGLE("SINGLE"),

    /** 仅组合内收费（划价按组合构成展开） */
    COMBO_ONLY("COMBO_ONLY");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ItemPriceFlag(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 SINGLE），非空；MP 写列与 JSON 序列化均取本值
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
    public static ItemPriceFlag fromCode(String code) {
        for (ItemPriceFlag flag : values()) {
            if (flag.code.equals(code)) {
                return flag;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的收费标记 code: " + code);
    }
}

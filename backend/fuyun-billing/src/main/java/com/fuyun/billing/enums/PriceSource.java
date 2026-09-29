package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 价格来源（charge_item_price.price_source 列值域，M13 Spec §4）：物价批文价与协议价二分，
 * 批文/协议文号经 approval_no 留痕。枚举规范同 FeeStatus。
 */
public enum PriceSource {

    /** 物价批文（政府定价/指导价） */
    OFFICIAL_DOC("OFFICIAL_DOC"),

    /** 协议价（院内谈判/打包协议） */
    AGREEMENT("AGREEMENT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PriceSource(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 OFFICIAL_DOC），非空；MP 写列与 JSON 序列化均取本值
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
    public static PriceSource fromCode(String code) {
        for (PriceSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的价格来源 code: " + code);
    }
}

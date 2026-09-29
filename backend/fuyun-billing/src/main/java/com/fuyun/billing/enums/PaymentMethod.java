package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 支付方式（deposit_txn.payment_method / settlement.payment_details JSON method 字段值域，Spec §4）：
 * deposit_txn 列仅取 CASH/BANK/SCAN/ONLINE 四值；CARD_BALANCE（一卡通）/CHARGE_ON_CREDIT（挂账）
 * 仅 settlement 支付明细可含，服务层守卫分域。枚举规范同 FeeStatus。
 */
public enum PaymentMethod {

    /** 现金 */
    CASH("CASH"),

    /** 银行转账/银行卡 */
    BANK("BANK"),

    /** 扫码支付（微信/支付宝聚合） */
    SCAN("SCAN"),

    /** 线上支付（互联网医院通道） */
    ONLINE("ONLINE"),

    /** 一卡通余额（仅 settlement 支付明细，扣一卡通台账） */
    CARD_BALANCE("CARD_BALANCE"),

    /** 挂账（仅 settlement 支付明细，绿通挂账联动） */
    CHARGE_ON_CREDIT("CHARGE_ON_CREDIT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    PaymentMethod(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 CASH），非空；MP 写列与 JSON 序列化均取本值
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
    public static PaymentMethod fromCode(String code) {
        for (PaymentMethod method : values()) {
            if (method.code.equals(code)) {
                return method;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的支付方式 code: " + code);
    }
}

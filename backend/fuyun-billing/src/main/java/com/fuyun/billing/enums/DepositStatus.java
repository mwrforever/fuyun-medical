package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 押金账户状态（deposit_account.status 列值域，Spec §4/§5）：NORMAL/ARREARS 可记账；
 * SETTLED/CLOSED 终态拒缴存（原路语义，BILL-1023 前置校验）。枚举规范同 FeeStatus。
 */
public enum DepositStatus {

    /** 正常（余额高于预警阈值） */
    NORMAL("NORMAL"),

    /** 欠费（余额低于预警阈值，欠费提醒驱动） */
    ARREARS("ARREARS"),

    /** 已结算（出院结算抵扣完成，终态） */
    SETTLED("SETTLED"),

    /** 已关闭（账户销户，终态） */
    CLOSED("CLOSED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DepositStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 NORMAL），非空；MP 写列与 JSON 序列化均取本值
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
    public static DepositStatus fromCode(String code) {
        for (DepositStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的押金账户状态 code: " + code);
    }
}

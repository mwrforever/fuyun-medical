package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 退费分级（refund_request.refund_type 列值域，Spec §4）：按是否已结算与时间窗划分
 * 退费流程等级（当日更正免审直退、跨日/已结算退费须审批）。枚举规范同 FeeStatus。
 */
public enum RefundType {

    /** 当日更正（未跨日，免审直退） */
    DAY_CORRECTION("DAY_CORRECTION"),

    /** 跨日退费（已跨日但未结算） */
    CROSS_DAY("CROSS_DAY"),

    /** 已结算退费（须审批 + 医保撤销联动） */
    SETTLED_REFUND("SETTLED_REFUND");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    RefundType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 DAY_CORRECTION），非空；MP 写列与 JSON 序列化均取本值
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
    public static RefundType fromCode(String code) {
        for (RefundType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的退费分级 code: " + code);
    }
}

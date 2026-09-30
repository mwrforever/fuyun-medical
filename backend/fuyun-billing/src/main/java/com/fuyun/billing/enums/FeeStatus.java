package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 费用状态机（Spec §5）：PENDING→CONFIRMED→SETTLED→PART_REFUND/FULL_REFUND；
 * PENDING/CONFIRMED→CANCELLED（未结算作废）；GUARANTEED/BAD_DEBT 绿通挂账预留（随 M03 P1 后段）。
 * 枚举规范（backend 宪法 A.2-7，BindingStatus 实证同型）：code 字段 + {@code @EnumValue} + {@code @JsonValue}
 * getCode() + fromCode 双向映射，DB 列写业务 code（本域 code 恒等常量名）。
 */
public enum FeeStatus {

    /** 已生成待确认 */
    PENDING("PENDING"),

    /** 已确认入账 */
    CONFIRMED("CONFIRMED"),

    /** 已结算（只读基点，红线 2） */
    SETTLED("SETTLED"),

    /** 部分退费 */
    PART_REFUND("PART_REFUND"),

    /** 全额退费 */
    FULL_REFUND("FULL_REFUND"),

    /** 已作废 */
    CANCELLED("CANCELLED"),

    /** 绿通/挂账放行（P1 后启用） */
    GUARANTEED("GUARANTEED"),

    /** 坏账核销（P1 后启用） */
    BAD_DEBT("BAD_DEBT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    FeeStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PENDING），非空；MP 写列与 JSON 序列化均取本值
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
    public static FeeStatus fromCode(String code) {
        for (FeeStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的费用状态 code: " + code);
    }
}

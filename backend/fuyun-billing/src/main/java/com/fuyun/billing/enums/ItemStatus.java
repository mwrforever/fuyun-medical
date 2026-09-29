package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 收费项目状态（charge_item.status 列值域，M13 Spec §5）：INACTIVE 项目禁参与新计价
 * （BILL-1003 前置校验）；计价规则配置同值域复用。枚举规范同 FeeStatus。
 */
public enum ItemStatus {

    /** 启用（可参与计价） */
    ACTIVE("ACTIVE"),

    /** 停用（禁新计价，存量费用不受影响） */
    INACTIVE("INACTIVE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ItemStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ACTIVE），非空；MP 写列与 JSON 序列化均取本值
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
    public static ItemStatus fromCode(String code) {
        for (ItemStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的项目状态 code: " + code);
    }
}

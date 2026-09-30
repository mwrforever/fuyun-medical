package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import org.springframework.http.HttpStatus;

/**
 * 费用归属切分类型（billing.fee_ownership_split.split_type 列值域，V1001）：住院费用归属
 * 时间线三类行——入科起费锚点（日切床位费的在院判定起点）、转科归属切分点（床日费按时间线
 * 切分归 P3，本 PR 落切分点记录）、出院停费标记（日切任务据此跳过出院就诊）。枚举规范同 FeeStatus。
 */
public enum FeeSplitType {

    /** 入科起费锚点（inpatient.visit.admitted 消费落行；from_ward_id 为空） */
    ADMIT_START("ADMIT_START"),

    /** 转科归属切分（inpatient.visit.transferred 消费落行；from/to 病区齐备） */
    TRANSFER("TRANSFER"),

    /** 出院停费标记（inpatient.visit.discharge-requested 消费落行；日切跳过判定面） */
    DISCHARGE_STOP("DISCHARGE_STOP");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    FeeSplitType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ADMIT_START），非空；MP 写列与 JSON 序列化均取本值
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
    public static FeeSplitType fromCode(String code) {
        for (FeeSplitType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BizException(BillingErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的费用归属切分类型 code: " + code);
    }
}

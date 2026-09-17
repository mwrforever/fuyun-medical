package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 退费申请状态机（Spec §5）：DRAFT→PENDING_APPROVAL→APPROVED→EXECUTED；驳回归 REJECTED 终态。
 * 双人守卫在 APPROVED 前置校验（审批人≠申请人，BILL-1020）。枚举规范同 FeeStatus。
 */
public enum RefundStatus {

    /** 草稿（明细编辑中） */
    DRAFT("DRAFT"),

    /** 待审批（提交后等待双人守卫审批） */
    PENDING_APPROVAL("PENDING_APPROVAL"),

    /** 已审批通过（待执行退费） */
    APPROVED("APPROVED"),

    /** 已执行（原路退回完成，终态） */
    EXECUTED("EXECUTED"),

    /** 已驳回（终态，留 reject_reason 留痕） */
    REJECTED("REJECTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    RefundStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PENDING_APPROVAL），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static RefundStatus fromCode(String code) {
        for (RefundStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的退费状态 code: " + code);
    }
}

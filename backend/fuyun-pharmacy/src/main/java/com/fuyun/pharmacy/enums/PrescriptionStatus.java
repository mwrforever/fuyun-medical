package com.fuyun.pharmacy.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 处方状态机十值（Spec :132 逐字冻结）：CREATED→APPROVED（预检通过级同事务）→PENDING_FEE
 * （billing.fee.created 驱动）→PENDING_DISPENSE（outpatient.order.charged 驱动）→DISPENSING
 * （配药批次锁定）→DISPENSED→PART_RETURNED/FULL_RETURNED（billing.refund.approved 后终态）；
 * APPROVED/PENDING_FEE→CANCELLED；REJECTED/CANCELLED 终态。
 */
public enum PrescriptionStatus {

    /** 已开立（转瞬态：与 APPROVED 同事务完成） */
    CREATED("CREATED"),

    /** 审方通过（生效，发 created 事件） */
    APPROVED("APPROVED"),

    /** 待缴费（PENDING 费用已生成） */
    PENDING_FEE("PENDING_FEE"),

    /** 已缴费待调配 */
    PENDING_DISPENSE("PENDING_DISPENSE"),

    /** 配药中（批次锁定） */
    DISPENSING("DISPENSING"),

    /** 已发药（终态基点） */
    DISPENSED("DISPENSED"),

    /** 部分退药 */
    PART_RETURNED("PART_RETURNED"),

    /** 全额退药 */
    FULL_RETURNED("FULL_RETURNED"),

    /** 审方驳回（终态，P3） */
    REJECTED("REJECTED"),

    /** 已作废（终态） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    PrescriptionStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code，非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举（脏数据）
     */
    public static PrescriptionStatus fromCode(String code) {
        for (PrescriptionStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知的处方状态 code: " + code);
    }
}

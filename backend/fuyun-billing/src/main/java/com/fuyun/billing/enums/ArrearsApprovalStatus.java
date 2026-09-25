package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 挂账审批四态（billing.arrears_approval.status 列值域，Spec M-10）：出院欠费挂账单生命周期——
 * DRAFT 草稿（词表保完整，创建端点内联瞬时态）→ PENDING_APPROVAL 待审批 → APPROVED 已通过
 * （同事务发布 billing.arrears.approved，M04 消费解除出院费用拦截）/ REJECTED 已驳回（终态，
 * 不发事件）。枚举规范同 FeeStatus。
 */
public enum ArrearsApprovalStatus {

    /** 草稿（创建编排内联瞬时态，落库即 PENDING_APPROVAL） */
    DRAFT("DRAFT"),

    /** 待审批（创建端点落库态；approve/reject 唯一可决出边） */
    PENDING_APPROVAL("PENDING_APPROVAL"),

    /** 已通过（发布 billing.arrears.approved 放行凭证） */
    APPROVED("APPROVED"),

    /** 已驳回（终态，欠费须另行结清或重新申请） */
    REJECTED("REJECTED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ArrearsApprovalStatus(String code) {
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
    public static ArrearsApprovalStatus fromCode(String code) {
        for (ArrearsApprovalStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的挂账审批状态 code: " + code);
    }
}

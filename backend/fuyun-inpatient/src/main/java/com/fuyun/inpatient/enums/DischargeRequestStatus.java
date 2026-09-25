package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 出院申请状态五值（V907 discharge_request.status 值域，04-inpatient Spec §5 状态机冻结）：
 * REQUESTED 已申请（在途清理与费用预审中）→ READY 预审通过（待结算离院）/ BLOCKED 预审未通过
 * （欠费挂账审批中——凭 billing.arrears.approved 转 READY）；READY → COMPLETED 离院完成（终态，
 * 离院确认双条件满足）；REQUESTED → CANCELLED 取消出院（终态，visit 回 ADMITTED 且长期医嘱
 * 不复活——需重新开立，Spec 边界）。申请落库时预审结果直接定 READY/BLOCKED（REQUESTED 为
 * 词表保完整的瞬时态——清理与预审同事务完成，无长停留窗口）。
 */
public enum DischargeRequestStatus {

    /** 已申请（在途清理与费用预审中；uk_visit_active 在途唯一覆盖态之一） */
    REQUESTED("REQUESTED"),

    /** 预审通过（费用结清，待出院结算与人工离院确认） */
    READY("READY"),

    /** 预审未通过（欠费挂账审批中，附欠费额快照；billing.arrears.approved 驱动转 READY） */
    BLOCKED("BLOCKED"),

    /** 离院完成（终态；离院确认双条件=READY+结算完成标记均满足后置位） */
    COMPLETED("COMPLETED"),

    /** 取消出院（终态；visit 回 ADMITTED，长期医嘱不复活需重新开立） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    DischargeRequestStatus(String code) {
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
     * 是否在途态（uk_visit_active 部分唯一索引覆盖面——一 visit 至多一条在途申请的判定口径）。
     *
     * @return true=REQUESTED/READY/BLOCKED（在途）
     */
    public boolean isActive() {
        return this == REQUESTED || this == READY || this == BLOCKED;
    }

    /**
     * code → 枚举查询侧。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static DischargeRequestStatus fromCode(String code) {
        for (DischargeRequestStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

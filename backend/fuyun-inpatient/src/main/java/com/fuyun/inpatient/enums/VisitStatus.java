package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 住院就诊状态五值（V902 inpatient_visit.status 值域，04-inpatient Spec §5 状态机冻结）：
 * REGISTERED 已登记待入科 → ADMITTED 在院（入科确认）→ DISCHARGE_REQUESTED 出院申请中 →
 * DISCHARGED 已出院（终态）；REGISTERED → CANCELLED 登记作废（终态，未入科回收）；
 * DISCHARGE_REQUESTED → ADMITTED 取消出院申请回在院。转科/转床为 ADMITTED 内属性变更
 * （发 inpatient.visit.transferred），独立于本状态机。
 */
public enum VisitStatus {

    /** 已登记待入科（visit_id 已签发，入院登记确认落库态） */
    REGISTERED("REGISTERED"),

    /** 在院（入科确认后；床位/科室/护理级别已登记） */
    ADMITTED("ADMITTED"),

    /** 出院申请中（在途清理与费用预审中，Task 9 承载） */
    DISCHARGE_REQUESTED("DISCHARGE_REQUESTED"),

    /** 已出院（终态） */
    DISCHARGED("DISCHARGED"),

    /** 登记作废（终态；未入科时作废回收） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    VisitStatus(String code) {
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
     * @return 对应枚举常量，非空；无匹配返回 null（脏数据防御交调用方）
     */
    public static VisitStatus fromCode(String code) {
        for (VisitStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 住院证（入院申请）状态四值（V902 admission.status 值域，04-inpatient Spec §5 状态机冻结）：
 * WAITING 待入院（候床队列）→ SCHEDULED 已预约/预住院检查中 → COMPLETED 已登记（签发 visit_id，终态）；
 * WAITING/SCHEDULED → CANCELLED 作废（终态）；SCHEDULED → WAITING 逾期未入院回队列重排（后续任务承载）。
 */
public enum AdmissionStatus {

    /** 待入院（候床队列主体；登记即建单入队） */
    WAITING("WAITING"),

    /** 已预约/预住院检查中（schedule 置位，携目标床位与预约日期） */
    SCHEDULED("SCHEDULED"),

    /** 已办理入院登记（签发 I 型 visit_id，终态） */
    COMPLETED("COMPLETED"),

    /** 已作废（终态） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AdmissionStatus(String code) {
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
    public static AdmissionStatus fromCode(String code) {
        for (AdmissionStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

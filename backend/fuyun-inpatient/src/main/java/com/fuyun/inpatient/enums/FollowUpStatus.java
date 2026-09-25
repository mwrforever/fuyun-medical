package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 随访计划状态三值（V907 follow_up_plan.status 值域，04-inpatient Spec §4 词表「待执行/已完成/
 * 已取消」的 code 承载）：离院确认生成 PENDING；触达执行完成置 DONE；随访方案变更或患者失联
 * 终止置 CANCELLED。触达通道经 M01 通知中心（通知中心缺位期降级为工作站列表可见——
 * 五大降级清单②）。
 */
public enum FollowUpStatus {

    /** 待执行（离院确认时生成；到期触达扫描本态） */
    PENDING("PENDING"),

    /** 已完成（随访执行完毕——电话接通/公众号已读/复诊已挂号） */
    DONE("DONE"),

    /** 已取消（随访方案变更或终止） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    FollowUpStatus(String code) {
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
}

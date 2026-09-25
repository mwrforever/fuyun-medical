package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执行计划状态三值（V906 order_execute_plan.status 值域，04-inpatient Spec §4）：PENDING
 * （待执行——三源开立初始态）→ EXECUTED（已执行——执行回签迁移，W-33 id 55 契约：回签时
 * 计划 PENDING→EXECUTED 且医嘱头联动推进）；CANCELLED（已作废——停嘱联动未来计划批量作废、
 * 转科编排长期计划作废两路径，作废后不可复活）。CANCELLED/EXECUTED 均为终态，本域无
 * EXECUTED→PENDING 回退路径。
 */
public enum PlanStatus {

    /** 待执行（三源开立初始态：临时转抄同步生成/长期日切分解/嘱托按需触发） */
    PENDING("PENDING"),

    /** 已执行（终态；执行回签迁移——医嘱头 TRANSFERRED→EXECUTING/COMPLETED 联动推进） */
    EXECUTED("EXECUTED"),

    /** 已作废（终态；停嘱联动未来计划作废/转科长期计划作废） */
    CANCELLED("CANCELLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    PlanStatus(String code) {
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
    public static PlanStatus fromCode(String code) {
        for (PlanStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

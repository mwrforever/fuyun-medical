package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 执行类型二值（V1106 order_execution.execution_type 值域）：GENERIC 通用给药 / INFUSION 输液。
 * 生成时一律 GENERIC——M04 转抄/计划冻结载荷不含用法字段，静脉判定不可得；transferType=drug
 * 的执行单在摆药签收衔接（Task 6 消费 pharmacy.dispense.completed 回填明细）时按用法升格
 * INFUSION 并建 infusion_monitor_link（brief 冻结口径）。
 */
public enum ExecutionType {

    /** 通用给药（生成默认态；无监测挂接链） */
    GENERIC("GENERIC"),

    /** 输液（静脉输注类；升格时点建输液监测挂接，走 IoT 告警联动链——Task 6 实装） */
    INFUSION("INFUSION");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    ExecutionType(String code) {
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
     * code → 枚举查询侧（非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static ExecutionType fromCode(String code) {
        for (ExecutionType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

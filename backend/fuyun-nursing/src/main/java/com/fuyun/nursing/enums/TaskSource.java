package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理任务来源六值（V805 nursing_task.source 值域，05-nursing Spec §4）：标识任务的生成渠道。
 * P1 落地双源（MANUAL 手工开立 / ASSESSMENT 评估联动随 Task 8）；ORDER_PLAN（医嘱计划转抄）/
 * INFUSION_ALARM（输液告急）/IOT_LINKAGE（IoT 联动）/ROUTINE（常规模板批量生成）四值随 P2
 * 执行域实装——枚举预留，P1 无写入方（值面先行冻结防后续契约变更）。
 */
public enum TaskSource {

    /** 医嘱计划转抄（P2 执行域写入方） */
    ORDER_PLAN("ORDER_PLAN"),

    /** 输液告急联动（P2 执行域写入方） */
    INFUSION_ALARM("INFUSION_ALARM"),

    /** IoT 设备联动（P2 写入方） */
    IOT_LINKAGE("IOT_LINKAGE"),

    /** 常规任务模板批量生成（P2 任务工作台写入方） */
    ROUTINE("ROUTINE"),

    /** 手工开立（护士站表单 / PDA 巡视打卡） */
    MANUAL("MANUAL"),

    /** 评估联动（护理评估单高危结果自动生成防范任务，Task 8 写入方） */
    ASSESSMENT("ASSESSMENT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TaskSource(String code) {
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
     * code → 枚举查询侧（创建入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static TaskSource fromCode(String code) {
        for (TaskSource value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

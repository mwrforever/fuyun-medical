package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理任务类型九值（V805 nursing_task.task_type 值域，05-nursing Spec §4）：覆盖给药、输液护理、
 * 翻身、巡视、标本采集、出入量监测、IoT 联动、评估提醒与手工九类护理任务。Task 8 评估高危
 * 联动扩 {@code PREVENTION}（防范任务）至十值——扩展只增不改，既有九值逐字冻结。
 */
public enum TaskType {

    /** 给药（口服/注射等用药执行提醒） */
    MEDICATION("MEDICATION"),

    /** 输液护理（输注巡视/换瓶/拔针等，P2 执行域联动） */
    INFUSION_CARE("INFUSION_CARE"),

    /** 翻身（压疮预防定时翻身） */
    TURN("TURN"),

    /** 巡视（病房巡视；PDA 巡视打卡落点，直落 COMPLETED） */
    PATROL("PATROL"),

    /** 标本采集（采血/留取标本提醒） */
    SPECIMEN("SPECIMEN"),

    /** 出入量监测（记出入量提醒） */
    IO_MONITOR("IO_MONITOR"),

    /** IoT 联动（设备阈值触发任务，P2 写入方，P1 恒无生产者） */
    IOT_LINKAGE("IOT_LINKAGE"),

    /** 评估提醒（复评到期提醒，P2 写入方） */
    ASSESS_REMIND("ASSESS_REMIND"),

    /** 手工任务（护士站手工开立） */
    MANUAL("MANUAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    TaskType(String code) {
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
    public static TaskType fromCode(String code) {
        for (TaskType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理任务来源六值（V805 nursing_task.source 值域，05-nursing Spec §4）：标识任务的生成渠道。
 * P1 落地双源（MANUAL 手工开立 / ASSESSMENT 评估联动随 Task 8）；IOT_LINKAGE 写入方随 P2 PR-3
 * Task 12 落地（NursingTaskLinkagePort 联动端口）；ROUTINE 写入方随 P2 PR-3 Task 9 落地（常规模板
 * 批量生成）。INFUSION_ALARM 维持预留枚举——P2 PR-3 Task 6 裁决：输液告急三路升级不新建任务、
 * 挂单经既有执行单上调（casEscalatePriorityBySourceRef），PR-3 Task 12 落地 IOT_LINKAGE 写入方
 * 后回核该裁决不变（无新增写入方）；ORDER_PLAN（医嘱计划转抄）随后续执行域扩展（值面先行冻结
 * 防后续契约变更）。
 */
public enum TaskSource {

    /** 医嘱计划转抄（后续执行域写入方，预留） */
    ORDER_PLAN("ORDER_PLAN"),

    /** 输液告急联动（预留：Task 6 裁决告警升级挂单上调既有任务，不新建——回核于 PR-3 Task 12） */
    INFUSION_ALARM("INFUSION_ALARM"),

    /** IoT 设备联动（P2 PR-3 Task 12 写入方：NursingTaskLinkagePort 幂等创建） */
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

package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理不良事件类别八值（V1107 adverse_event.category 值域，05-nursing Spec FU-M05-09）：
 * 上报表单必填枚举面，词表与列注释同源；统计面按本类别聚合计数（M19 护理质量指标消费，
 * 缺位注记）。值域冻结八类，扩充属 CF 契约变更（迁移 + 前端字典同步）。
 */
public enum AdverseEventCategory {

    /** 用药错误（给药/摆药/核对环节差错） */
    MEDICATION_ERROR("MEDICATION_ERROR"),

    /** 跌倒（患者跌倒/坠床事件） */
    FALL("FALL"),

    /** 压疮（压力性损伤事件） */
    PRESSURE_ULCER("PRESSURE_ULCER"),

    /** 管路滑脱（各类管路非计划拔管） */
    TUBE_SLIP("TUBE_SLIP"),

    /** 输血（输血反应/输血差错） */
    BLOOD_TRANFUSION("BLOOD_TRANFUSION"),

    /** 器械（医疗器械相关事件） */
    DEVICE("DEVICE"),

    /** 设施（医院设施/环境相关事件） */
    FACILITY("FACILITY"),

    /** 其他（不在上述七类的 events） */
    OTHER("OTHER");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    AdverseEventCategory(String code) {
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
     * code → 枚举查询侧（查询入参/上报表单显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static AdverseEventCategory fromCode(String code) {
        for (AdverseEventCategory value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

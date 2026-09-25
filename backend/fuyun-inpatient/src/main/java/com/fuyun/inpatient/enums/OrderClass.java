package com.fuyun.inpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 医嘱分类两值（V904 medical_order.order_class 值域，04-inpatient Spec §4 冻结）：
 * LONG 长期医嘱——携频次（freq_code 非空）经审核/转抄后按 order_frequency 拆分执行计划，
 * 转科时批量自动停嘱；STAT 临时医嘱——无频次（freq_code 为 NULL）单次执行即刻生效，
 * 转科时随患者保留（计划三分规则的分类依据）。standby_flag 嘱托标记仅 LONG 可用
 * （STAT+standby 开立即拒——IP-1022，四层校验第四层）。
 */
public enum OrderClass {

    /** 长期医嘱（频次必填；按频次拆分执行计划；转科停嘱面） */
    LONG("LONG"),

    /** 临时医嘱（单次即刻执行；转科随患者保留） */
    STAT("STAT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    OrderClass(String code) {
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
    public static OrderClass fromCode(String code) {
        for (OrderClass value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

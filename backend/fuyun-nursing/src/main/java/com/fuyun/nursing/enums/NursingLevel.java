package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理级别三值（V801 nursing_ward_patient.nursing_level 值域）：SPECIAL 特级 / CRITICAL 病重 /
 * NORMAL 普通。权威在 M04 住院医嘱（护理级别医嘱），本模块仅为病区一览视图属性承载——
 * P1 过渡通道由操作者录入展示值，P2 由 inpatient.visit.admitted 事件链携带刷新。
 */
public enum NursingLevel {

    /** 特级护理 */
    SPECIAL("SPECIAL"),

    /** 病重护理 */
    CRITICAL("CRITICAL"),

    /** 普通护理（登记缺省值，与 V801 列默认一致） */
    NORMAL("NORMAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    NursingLevel(String code) {
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
     * code → 枚举查询侧（入区登记/分配入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static NursingLevel fromCode(String code) {
        for (NursingLevel value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

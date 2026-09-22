package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 出入量类型二值（V804 io_record.io_type 值域，Spec :111）：INTAKE 入量（静脉输液/口服/鼻饲/
 * 输血）/ OUTPUT 出量（尿/便/呕吐/引流/穿刺液）。小结平衡值 = 总入量 - 总出量（调研依据 6）。
 */
public enum IoType {

    /** 入量（入量 ml，重量类 g） */
    INTAKE("INTAKE"),

    /** 出量（出量 ml，重量类 g） */
    OUTPUT("OUTPUT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    IoType(String code) {
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
     * code → 枚举查询侧（录入入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static IoType fromCode(String code) {
        for (IoType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

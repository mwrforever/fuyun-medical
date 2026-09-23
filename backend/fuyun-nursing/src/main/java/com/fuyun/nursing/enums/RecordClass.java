package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 护理记录类别二值（V802 nursing_record.record_class 值域）：GENERAL 一般护理记录 /
 * CRITICAL 病重病危护理记录。表格式护理记录按患者护理级别选用载体（Spec :113 状态机所属域），
 * 自动归集观察行一律取 GENERAL（病区默认）。
 */
public enum RecordClass {

    /** 一般护理记录（默认类别） */
    GENERAL("GENERAL"),

    /** 病重病危护理记录 */
    CRITICAL("CRITICAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    RecordClass(String code) {
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
    public static RecordClass fromCode(String code) {
        for (RecordClass value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

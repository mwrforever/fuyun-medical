package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 不良事件严重度分级四值（V1107 adverse_event.severity_class 值域，05-nursing Spec
 * FU-M05-09）：I 级最重、IV 级最轻；I/II 级触发 24 小时强制上报时限（report_deadline=
 * occurred_at+24h，超时只留痕不拒绝——非惩罚原则）。词表与列注释同源，值域冻结。
 */
public enum SeverityClass {

    /** I 级（最重；24 小时强制上报时限） */
    CLASS_I("I"),

    /** II 级（重；24 小时强制上报时限） */
    CLASS_II("II"),

    /** III 级（中；时限口径归应用层规则承载，不预置 deadline） */
    CLASS_III("III"),

    /** IV 级（最轻；时限口径归应用层规则承载，不预置 deadline） */
    CLASS_IV("IV");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    SeverityClass(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（I/II/III/IV），非空
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 24 小时强制上报时限判定（deadline 预置依据）：仅 I/II 级预置 report_deadline，
     * III/IV 级时限口径不预置（V1107 列注释冻结口径）。
     *
     * @return true=I/II 级（强制 24 小时上报时限）
     */
    public boolean requiresDeadline() {
        return this == CLASS_I || this == CLASS_II;
    }

    /**
     * code → 枚举查询侧（上报表单/查询入参显式格式校验用，非法值拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static SeverityClass fromCode(String code) {
        for (SeverityClass value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

package com.fuyun.nursing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 出入量小结类型二值（V804 io_summary.summary_type 值域，Spec :112）：SHIFT 班次小结（按病区
 * 班次定义期间求和）/ 24H 24 小时总结（当日 00:00–24:00 全天求和，大夜班每 24 小时一次——
 * 调研依据 6）。小结与 24h 总结随体温单 DAILY_VALUE 条目落卡，红双线标识由前端渲染；每型
 * 携带其体温单日行值类型键（daily_value_type，V802 词表冻结值）。
 */
public enum IoSummaryType {

    /** 班次小结（体温单日行值类型键=IO_SUMMARY_SHIFT，班次归属由 io_summary.shift_code 承载） */
    SHIFT("SHIFT", "IO_SUMMARY_SHIFT"),

    /** 24 小时总结（体温单日行值类型键=IO_SUMMARY_24H） */
    HOURS_24("24H", "IO_SUMMARY_24H");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用 */
    @EnumValue
    private final String code;

    /** 体温单日行值类型键（V802 daily_value_type 词表；appendDailyValue 的 dailyValueType 入参） */
    private final String chartValueType;

    IoSummaryType(String code, String chartValueType) {
        this.code = code;
        this.chartValueType = chartValueType;
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
     * 取体温单日行值类型键。
     *
     * @return daily_value_type（如 IO_SUMMARY_24H），非空
     */
    public String getChartValueType() {
        return chartValueType;
    }

    /**
     * code → 枚举查询侧（小结入参显式格式校验用，非法值 NS-1019 拒收）。
     *
     * @param code 存储值，非空
     * @return 对应枚举常量，非空；无匹配返回 null（调用方显式判空拒绝，不做裸异常）
     */
    public static IoSummaryType fromCode(String code) {
        for (IoSummaryType value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 指标类别枚举（iot.iot_metric_dict.category 列值域，V1007）：MDC 术语字典的四大类目口径
 * （14-iot.md §105：体征/波形/报警/设备状态）。类别决定管理台字典分组展示与遥测归一后的
 * 消费通道（体征入护理评估、报警入告警引擎、设备状态入设备运维）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum MetricCategory {

    /** 体征：心率/血压/血氧等离散生理测量（入护理评估与临床视图） */
    VITAL_SIGN("VITAL_SIGN"),

    /** 波形：ECG/呼吸阻抗等连续波形通道（入波形存储与监护界面） */
    WAVEFORM("WAVEFORM"),

    /** 报警：设备报警事件通道语义（入告警引擎评估） */
    ALARM("ALARM"),

    /** 设备状态：电量/探头脱落等设备运维语义（入设备运维视图） */
    DEVICE_STATUS("DEVICE_STATUS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MetricCategory(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 VITAL_SIGN），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台筛选参数；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法入参），
     *                                  建议调用方按参数错误/数据异常处置
     */
    public static MetricCategory fromCode(String code) {
        for (MetricCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("未知的指标类别 code: " + code);
    }
}

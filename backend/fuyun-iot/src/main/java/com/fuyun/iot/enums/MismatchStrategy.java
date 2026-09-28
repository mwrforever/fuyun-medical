package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 遥测失配策略枚举（iot.iot_metric_mapping.mismatch_strategy 列值域，V1007）：物模型属性在
 * 映射缺失或 MDC 归一失败时的遥测处置口径。当前唯一词表项 RAW_PASSTHROUGH——失配期间遥测
 * 按未映射属性原样入库（metric_code 记原生属性名）不静默丢弃（14-iot.md FU-M14-02 红线）；
 * 词表扩充（如丢弃/隔离面）随后续任务经宪法修订顺延，禁止私自扩展。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum MismatchStrategy {

    /** 原文透传：未映射属性原样入库，metric_code 记原生属性名（当前唯一策略） */
    RAW_PASSTHROUGH("RAW_PASSTHROUGH");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MismatchStrategy(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 RAW_PASSTHROUGH），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），
     *                                  建议调用方按数据异常处置
     */
    public static MismatchStrategy fromCode(String code) {
        for (MismatchStrategy strategy : values()) {
            if (strategy.code.equals(code)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("未知的失配策略 code: " + code);
    }
}

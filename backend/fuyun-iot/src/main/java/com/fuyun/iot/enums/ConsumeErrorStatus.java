package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消费错误处置状态枚举（iot.iot_consume_error_log.status 列值域，M14 Spec §5 处置状态机）。
 *
 * <p>状态机：PENDING 待处理 → REPLAYED 已重放 / ABANDONED 已放弃（必填原因）；
 * REPLAYED 前可多次重放（replay_count 累加）。P0 只建写路径（错误落库恒 PENDING），
 * 重放/放弃管理端点随 P1。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum ConsumeErrorStatus {

    /** 待处理：错误落库初始值 */
    PENDING("PENDING"),

    /** 已重放：重放成功（历史重放次数经 replay_count 回溯） */
    REPLAYED("REPLAYED"),

    /** 已放弃：人工确认不重放（必填原因） */
    ABANDONED("ABANDONED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ConsumeErrorStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PENDING），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static ConsumeErrorStatus fromCode(String code) {
        for (ConsumeErrorStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的消费错误处置状态 code: " + code);
    }
}

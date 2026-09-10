package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 遥测接入链路来源枚举（iot.iot_telemetry.source 列值域，值域与 CF-7 标准遥测消息 source 字符串一致）。
 *
 * <p>IOTDA 主链路（模式 A/B/C 经华为云 IoTDA AMQP 消费，P0 兜底通道同标 IOTDA）与 HL7 辅链路
 * （模式 D 经 M20 集成引擎，遥测移交 SPI 移交）双来源；P0 全部落 IOTDA，HL7 随模式 D 辅链路实装。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum TelemetrySource {

    /** IOTDA 主链路：AMQP 消费与 HTTP 兜底通道共用（P0 唯一取值） */
    IOTDA("IOTDA"),

    /** HL7 辅链路：模式 D 经 M20 集成引擎（随遥测移交 SPI 实装启用） */
    HL7("HL7");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TelemetrySource(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 IOTDA），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或 CF-7 报文 source 字段；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（报文值域越界或脏数据），
     *                                  建议调用方按解析失败处置
     */
    public static TelemetrySource fromCode(String code) {
        for (TelemetrySource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知的遥测来源 code: " + code);
    }
}

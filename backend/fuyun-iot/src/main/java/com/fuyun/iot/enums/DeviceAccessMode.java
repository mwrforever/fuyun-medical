package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 设备接入模式枚举（iot.iot_device.access_mode 列值域，总 Spec 5.1 设备接入方式矩阵）。
 *
 * <p>四路同构（M14 FU-M14-05）：模式 A/B/C 经 IoTDA 主链路（AMQP）接入，模式 D 经 HL7 集成引擎
 * 辅链路（M20 报文匹配）接入；四路遥测统一收敛为 CF-7 标准消息模型。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum DeviceAccessMode {

    /** 模式 A：直连（设备原生 MQTT 直连 IoTDA） */
    A("A"),

    /** 模式 B：串口服务器（RS232 设备经串口服务器转 MQTT） */
    B("B"),

    /** 模式 C：边缘适配器/网关（BLE/RFID 等短距设备经边缘网关汇聚转 MQTT） */
    C("C"),

    /** 模式 D：HL7 引擎（HL7 MLLP 报文经 M20 集成引擎接入） */
    D("D");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DeviceAccessMode(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 A），非空；MP 写列与 JSON 序列化均取本值
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
    public static DeviceAccessMode fromCode(String code) {
        for (DeviceAccessMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的设备接入模式 code: " + code);
    }
}

package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 边缘网关接入模式枚举（iot.iot_gateway.mode 列值域，14-iot 领域模型 FU-M14-12）：模式 B/C
 * 网关档案的形态词表——直连（A）与 HL7 引擎（D）不经网关，不在本词表。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum GatewayMode {

    /** 模式 B：串口服务器（RS232 设备经串口服务器汇聚转 MQTT） */
    B("B"),

    /** 模式 C：边缘适配器/网关（BLE/RFID 等短距设备经边缘网关汇聚转 MQTT） */
    C("C");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    GatewayMode(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 B），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台请求；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据），建议调用方按数据异常处置
     */
    public static GatewayMode fromCode(String code) {
        for (GatewayMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的网关接入模式 code: " + code);
    }
}

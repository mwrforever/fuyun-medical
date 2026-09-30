package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 边缘网关状态枚举（iot.iot_gateway.status 列值域，14-iot 领域模型「在线/离线/维护」三态）。
 *
 * <p>与设备五态（DeviceStatus）解耦：网关状态为管理台人工维护的档案态（在线状态实时面经 IoTDA
 * 设备状态数据源归设备档案，网关行不重复承载实时状态机），三态词表独立防混用。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} +
 * {@code fromCode} 双向映射。
 */
public enum GatewayStatus {

    /** 在线：网关正常服务 */
    ONLINE("ONLINE"),

    /** 离线：网关失联/停机 */
    OFFLINE("OFFLINE"),

    /** 维护：网关检修/升级窗口（OTA 预留，P1 FU-M14-12 完整化） */
    MAINTENANCE("MAINTENANCE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    GatewayStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ONLINE），非空；MP 写列与 JSON 序列化均取本值
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
    public static GatewayStatus fromCode(String code) {
        for (GatewayStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的网关状态 code: " + code);
    }
}

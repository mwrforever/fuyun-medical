package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 命令下发通道枚举（iot.iot_command_log.deliver_mode 列值域，V1009 / 14-iot FU-M14-09）：
 * 按设备在线预检自动选道——快照 ONLINE 走 SYNC 在线同步（等待回执更新终态，超时置 TIMEOUT），
 * OFFLINE 走 ASYNC 离线异步（置 ISSUED 待结果帧回推；送达时间不可控，由结果帧驱动终态）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum CommandDeliverMode {

    /** 在线同步：Registry.sendCommand 阻塞等待设备回执（有界超时） */
    SYNC("SYNC"),

    /** 离线异步：命令受理后置 ISSUED，设备上线送达执行，结果经命令状态帧回推终态 */
    ASYNC("ASYNC");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    CommandDeliverMode(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 SYNC），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据或非法入参），
     *                      建议调用方按数据异常处置
     */
    public static CommandDeliverMode fromCode(String code) {
        for (CommandDeliverMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的命令下发通道 code: " + code);
    }
}

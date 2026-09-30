package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 遥测数据质量枚举（iot.iot_telemetry.quality 列值域，值域与 CF-7 标准遥测消息 quality 字符串一致）。
 *
 * <p>质量语义（M14 FU-M14-05 解析校验五步）：GOOD 正常；SUSPECT 可疑（时间偏差超阈值等，标注不
 * 丢弃）；BAD 异常（生理极限硬校验命中 / 报文 value 非数值）。标注不阻断是本模块口径——数据质量
 * 参差时保留原始行供质量统计（FU-M14-11 P1），禁止静默丢弃。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum TelemetryQuality {

    /** 正常：数值解析定型且校验通过 */
    GOOD("GOOD"),

    /** 可疑：时间偏差超阈值等，标注不丢弃 */
    SUSPECT("SUSPECT"),

    /** 异常：生理极限硬校验命中或报文 value 非数值（保留原文标 BAD） */
    BAD("BAD");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TelemetryQuality(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 GOOD），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或 CF-7 报文 quality 字段；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（报文值域越界或脏数据），
     *                      建议调用方按解析失败处置
     */
    public static TelemetryQuality fromCode(String code) {
        for (TelemetryQuality quality : values()) {
            if (quality.code.equals(code)) {
                return quality;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的遥测数据质量 code: " + code);
    }
}

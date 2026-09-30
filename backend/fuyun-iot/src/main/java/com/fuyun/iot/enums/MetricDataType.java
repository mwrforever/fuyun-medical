package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 指标数据类型枚举（iot.iot_metric_dict.data_type 列值域，V1007）：MDC 字典项的承载形态声明，
 * 驱动遥测归一后的序列化与统计口径（NUMERIC 可入生理统计，TEXT/JSON 仅原文承载，与 W-7
 * 非数值承载口径一致）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum MetricDataType {

    /** 数值：可入生理极限校验与统计口径（如心率次每分） */
    NUMERIC("NUMERIC"),

    /** 文本：非数值标量原文承载（如设备型号串） */
    TEXT("TEXT"),

    /** 结构化：对象/数组形态紧凑 JSON 承载（如多点参数包） */
    JSON("JSON");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    MetricDataType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 NUMERIC），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台维护参数；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据或非法入参），
     *                      建议调用方按参数错误/数据异常处置
     */
    public static MetricDataType fromCode(String code) {
        for (MetricDataType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的指标数据类型 code: " + code);
    }
}

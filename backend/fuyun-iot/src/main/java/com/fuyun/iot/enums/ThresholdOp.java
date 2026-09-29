package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 阈值比较方向枚举（iot.iot_alarm_rule.compare_op 列值域，仅 THRESHOLD 规则有语义）。
 *
 * <p>存储值直接承载比较符号（Spec 14-iot 规则列 compare_op 词表形态，如「&gt;150」的&gt;），
 * 告警引擎按方向判定越限与恢复带边界。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum ThresholdOp {

    /** 高于：采集值 &gt; 阈值判越限（恢复带边界 = 阈值+恢复带） */
    GT(">"),

    /** 低于：采集值 &lt; 阈值判越限（恢复带边界 = 阈值-恢复带） */
    LT("<");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ThresholdOp(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 &gt;），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台请求体；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据或非法请求值），
     *                      建议调用方按校验失败/数据异常处置
     */
    public static ThresholdOp fromCode(String code) {
        for (ThresholdOp op : values()) {
            if (op.code.equals(code)) {
                return op;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的阈值比较方向 code: " + code);
    }
}

package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 告警状态枚举（iot.iot_alarm.status 列值域，FU-M14-08 告警生命周期）。
 *
 * <p>状态机：ACTIVE 活跃（触发初态，抑制①期内重复触发仅聚合计数）→ ACKNOWLEDGED 已确认
 * （人工确认，抑制⑤升级链停止）→ CLOSED 已关闭（终态，人工关闭原因必填；活跃唯一索引随之
 * 释放，同源可再次触发新行）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum AlarmStatus {

    /** 活跃：触发初态（同设备同规则至多一条，部分唯一索引 uk_iot_alarm_active 兜底） */
    ACTIVE("ACTIVE"),

    /** 已确认：人工已知晓处置中（升级链停止；仍可关闭） */
    ACKNOWLEDGED("ACKNOWLEDGED"),

    /** 已关闭：终态（关闭原因必填；活跃唯一索引释放） */
    CLOSED("CLOSED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    AlarmStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ACTIVE），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或查询参数；非空
     * @return 对应枚举常量，非空
     * @throws BizException IOT-1026（400）：code 无对应枚举常量（脏数据或非法请求值），
     *                      建议调用方按校验失败/数据异常处置
     */
    public static AlarmStatus fromCode(String code) {
        for (AlarmStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的告警状态 code: " + code);
    }
}

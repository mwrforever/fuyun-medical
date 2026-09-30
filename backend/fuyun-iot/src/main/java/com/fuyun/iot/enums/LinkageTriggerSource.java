package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 联动触发来源枚举（iot.linkage_rule.trigger_source 列值域，FU-M14-10 三类触发源）。
 *
 * <p>P2 PR-2 Task 9 仅接线 ALARM_TRIGGERED（IotAlarmEventListener 消费 iot.alarm.triggered 自
 * 事件）；TELEMETRY_ANOMALY/DEVICE_STATUS 两源随词表预留在位，后续任务按同款执行器入口接线。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum LinkageTriggerSource {

    /** 告警触发：iot.alarm.triggered 事件驱动（P2 唯一接线源） */
    ALARM_TRIGGERED("ALARM_TRIGGERED"),

    /** 遥测异常：iot.telemetry.anomaly 事件驱动（源预留，后续接线） */
    TELEMETRY_ANOMALY("TELEMETRY_ANOMALY"),

    /** 设备状态变更：iot.device.status-changed 事件驱动（源预留，后续接线） */
    DEVICE_STATUS("DEVICE_STATUS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    LinkageTriggerSource(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ALARM_TRIGGERED），非空；MP 写列与 JSON 序列化均取本值
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
    public static LinkageTriggerSource fromCode(String code) {
        for (LinkageTriggerSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        // 词表外 code 收口（BE-C3-05）：BizException 400 + IOT-1026 直达边界渲染 ProblemDetail，
        // MQ 解析链调用方（TelemetryFrameParser/快照读取）就地捕获包装，毒丸/降级语义不变
        throw new BizException(IotErrorCode.ENUM_CODE_INVALID, HttpStatus.BAD_REQUEST, "未知的联动触发来源 code: " + code);
    }
}

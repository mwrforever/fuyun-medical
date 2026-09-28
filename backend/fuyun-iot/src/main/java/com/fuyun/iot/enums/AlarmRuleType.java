package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 告警规则类型枚举（iot.iot_alarm_rule.rule_type 列值域，FU-M14-08 三类规则源）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum AlarmRuleType {

    /** 设备告警透传：IoTDA device.alarm 帧命中（透传规则，按帧告警名匹配规则指标编码） */
    DEVICE_ALARM("DEVICE_ALARM"),

    /** 阈值：遥测越限判定（越阈值持续时长触发，恢复带内不重复触发） */
    THRESHOLD("THRESHOLD"),

    /** 离线：ONLINE 设备 last_online_at 距今超离线判定秒（数据断流告警） */
    OFFLINE("OFFLINE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    AlarmRuleType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 THRESHOLD），非空；MP 写列与 JSON 序列化均取本值
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
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法请求值），
     *                                  建议调用方按校验失败/数据异常处置
     */
    public static AlarmRuleType fromCode(String code) {
        for (AlarmRuleType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的告警规则类型 code: " + code);
    }
}

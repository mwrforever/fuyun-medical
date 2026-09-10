package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 设备档案状态枚举（iot.iot_device.status 列值域，M14 Spec §5 设备状态机）。
 *
 * <p>状态机：INACTIVE 未激活（已注册未首次上线）→ ONLINE ⇄ OFFLINE（在线/离线由 IoTDA 设备状态
 * 数据源驱动，网关代子设备上下线）；ONLINE/OFFLINE → ABNORMAL 异常（频繁断连/数据断流/异常值超标）
 * → 恢复回 ONLINE/OFFLINE；任意状态 → DISABLED 停用（可逆回 INACTIVE）。状态每次迁移发布
 * iot.device.status-changed 事件（V403 已登记）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum DeviceStatus {

    /** 未激活：已注册未首次上线 */
    INACTIVE("INACTIVE"),

    /** 在线：IoTDA 设备状态数据源驱动（设备状态帧 apply 时更新 last_online_at） */
    ONLINE("ONLINE"),

    /** 离线：IoTDA 设备状态数据源驱动（设备状态帧 apply 时更新 last_offline_at） */
    OFFLINE("OFFLINE"),

    /** 异常：频繁断连/数据断流/异常值超标（规则判定或人工标记），恢复回 ONLINE/OFFLINE */
    ABNORMAL("ABNORMAL"),

    /** 停用：保留档案与历史数据，可逆回 INACTIVE */
    DISABLED("DISABLED");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    DeviceStatus(String code) {
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
     * @param code 存储值，来源：DB 列读取或 AMQP 状态帧 status 字段；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或状态帧非法值），
     *                                  建议调用方按解析失败/数据异常处置
     */
    public static DeviceStatus fromCode(String code) {
        for (DeviceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的设备状态 code: " + code);
    }
}

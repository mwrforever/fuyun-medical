package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 命令状态枚举（iot.iot_command_log.status 列值域，V1009 / 14-iot §词汇）：命令下发状态机
 * ISSUED 已下发 → DELIVERED 已送达（设备确认收到，结果帧驱动）→ SUCCESS 执行成功 / FAILED
 * 执行失败；ISSUED 停留超时 → TIMEOUT 超时（终态）。终态（SUCCESS/FAILED/TIMEOUT）不可变更
 * （终态 CAS 以旧状态 ∈ ISSUED/DELIVERED 限定兜底，IotCommandLogMapper.casTerminal）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum CommandStatus {

    /** 已下发：命令行落库初态（同步等待回执/异步待结果帧） */
    ISSUED("ISSUED"),

    /** 已送达：设备确认收到（异步结果帧 DELIVERED 驱动的中间态） */
    DELIVERED("DELIVERED"),

    /** 执行成功（终态）：同步回执正常返回或结果帧 SUCCESS */
    SUCCESS("SUCCESS"),

    /** 执行失败（终态）：注册中心受理失败或结果帧失败态 */
    FAILED("FAILED"),

    /** 超时（终态）：同步等待回执超时或结果帧超时/过期态 */
    TIMEOUT("TIMEOUT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    CommandStatus(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ISSUED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或结果帧 status 字段；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法入参），
     *                                  建议调用方按数据异常/毒丸处置
     */
    public static CommandStatus fromCode(String code) {
        for (CommandStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的命令状态 code: " + code);
    }
}

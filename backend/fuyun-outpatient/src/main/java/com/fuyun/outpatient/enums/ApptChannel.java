package com.fuyun.outpatient.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 预约渠道枚举（outpatient.appointment.channel 列值域，M03 Spec §4 六渠道词表）：P1 实装
 * WINDOW/KIOSK（当日挂号一步 TAKEN）与 PORTAL（支付时限占位 RESERVED）三渠道，
 * MINIAPP/CONSULT/EXTERNAL 为词表预留位（预约入口校验拒绝，OP-1019）。
 *
 * <p>枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ fromCode 双向映射。
 */
public enum ApptChannel {

    /** 窗口（收费/挂号窗口，当日挂号主渠道） */
    WINDOW("WINDOW"),

    /** 自助机（自助终端当日挂号） */
    KIOSK("KIOSK"),

    /** 公众号（portal 匿名通道，支付时限占位） */
    PORTAL("PORTAL"),

    /** 小程序（词表预留位，P1 未开放预约入口） */
    MINIAPP("MINIAPP"),

    /** 诊间（医生诊间预约加号，词表预留位） */
    CONSULT("CONSULT"),

    /** 外联平台（经 M20 外联渠道，词表预留位） */
    EXTERNAL("EXTERNAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ApptChannel(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PORTAL），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或预约请求入参；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或词表外入参），调用方按
     *                                  OP-1019 参数格式校验处置
     */
    public static ApptChannel fromCode(String code) {
        for (ApptChannel channel : values()) {
            if (channel.code.equals(code)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("未知的预约渠道 code: " + code);
    }
}

package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 命令安全等级枚举（iot.iot_product_command.safety_level 列值域，V1007）：设备命令按医疗风险
 * 分级管控（14-iot FU-M14-02/FU-M14-09 白名单数据源）。安全级（如设置工作模式）默认放行下发；
 * 治疗级（如调节输液速率、除颤参数）默认禁放行，须持确认凭证二次确认（COMMAND_NOT_ALLOWED
 * 闸门语义随 Task 后续任务实装）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue}（MP DB 列映射）+
 * {@code @JsonValue}（JSON 输出 code）+ {@code fromCode} 双向映射。
 */
public enum CommandSafetyLevel {

    /** 安全级：默认放行下发（allowed=true） */
    SAFETY("SAFETY"),

    /** 治疗级：默认禁放行（allowed=false），下发须二次确认凭证 */
    TREATMENT("TREATMENT");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    CommandSafetyLevel(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 TREATMENT），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或管理台标注参数；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法入参），
     *                                  建议调用方按参数错误/数据异常处置
     */
    public static CommandSafetyLevel fromCode(String code) {
        for (CommandSafetyLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知的命令安全等级 code: " + code);
    }

    /**
     * 级别默认放行面（V1007 allowed 列落行默认契约）：安全级默认放行、治疗级默认禁放行。
     *
     * @return 该级别在管理台未显式指定 allowed 时的默认值；SAFETY→true，TREATMENT→false
     */
    public boolean defaultAllowed() {
        return this == SAFETY;
    }
}

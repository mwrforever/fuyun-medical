package com.fuyun.iot.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消费失败阶段枚举（iot.iot_consume_error_log.error_stage 列值域，M14 Spec §4）。
 *
 * <p>AMQP 主链路四阶段失败统一落点的阶段标注：PARSE 解析（非 JSON/缺必填字段毒丸）、
 * VALIDATE 校验、PERSIST 落库；解析失败由消费侧落 PARSE 行后确认抛弃（毒丸隔离，
 * FU-M14-01），管理界面按阶段可查可重放（P0 只建写路径）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + {@code fromCode}。
 */
public enum ConsumeErrorStage {

    /** 解析失败：非 JSON / 缺必填字段（毒丸隔离主场景） */
    PARSE("PARSE"),

    /** 校验失败：字段合法但业务校验不通过 */
    VALIDATE("VALIDATE"),

    /** 落库失败：批量写库异常（IoTDA 重推由唯一约束兜底幂等） */
    PERSIST("PERSIST");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    ConsumeErrorStage(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 PARSE），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或消费侧传入的阶段字面量；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或调用方传参非法），
     *                                  建议调用方修正传参
     */
    public static ConsumeErrorStage fromCode(String code) {
        for (ConsumeErrorStage stage : values()) {
            if (stage.code.equals(code)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知的消费失败阶段 code: " + code);
    }
}

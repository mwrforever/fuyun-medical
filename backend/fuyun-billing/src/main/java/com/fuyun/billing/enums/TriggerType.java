package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 计价触发型七值（pricing_rule.trigger_type / fee_record.trigger_point 列值域，Spec §4）：
 * 事件驱动计价分流配置面；DURATION（日切分解）消费实现随 P2。枚举规范同 FeeStatus。
 */
public enum TriggerType {

    /** 门诊开单确认触发 */
    ORDER_CONFIRMED("ORDER_CONFIRMED"),

    /** 处方生效触发 */
    PRESCRIPTION_EFFECTIVE("PRESCRIPTION_EFFECTIVE"),

    /** 执行完成触发 */
    EXECUTED("EXECUTED"),

    /** 登记触发（住院登记等） */
    REGISTERED("REGISTERED"),

    /** 扫码触发（检查/治疗扫码计费） */
    SCANNED("SCANNED"),

    /** 日切分解触发（DURATION 消费随 P2） */
    DURATION("DURATION"),

    /** 手工触发（操作者/理由必填） */
    MANUAL("MANUAL");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    TriggerType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 ORDER_CONFIRMED），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据），建议上游按数据异常处置
     */
    public static TriggerType fromCode(String code) {
        for (TriggerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的计价触发型 code: " + code);
    }
}

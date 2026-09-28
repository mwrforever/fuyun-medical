package com.fuyun.ward.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 呼叫类型枚举（ward_call.call_type 四值词表，V1100 列注释冻结）：任务转换默认仅 EMERGENCY 类
 * （路由规则 task_convert_flag 病区可开——M05 任务创建 PR-3 闭合）。
 * 枚举规范（backend 宪法 A.2-7）：code 字段 + {@code @EnumValue} + {@code @JsonValue} + fromCode。
 */
public enum CallType {

    /** 普通呼叫 */
    NORMAL("NORMAL"),

    /** 紧急呼叫（任务转换默认类） */
    EMERGENCY("EMERGENCY"),

    /** 输液呼叫（iot.alarm.triggered 输液告急落行，source_ref=告警号） */
    INFUSION("INFUSION"),

    /** 服务呼叫（陪护/送餐等生活服务类） */
    SERVICE("SERVICE");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue）共用的业务 code */
    @EnumValue
    private final String code;

    CallType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 EMERGENCY），非空；MP 写列与 JSON 序列化均取本值
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * code → 枚举双向映射的查询侧。
     *
     * @param code 存储值，来源：DB 列读取或消费载荷文本；非空
     * @return 对应枚举常量，非空
     * @throws IllegalArgumentException code 无对应枚举常量（脏数据或非法载荷值），
     *                                  建议调用方按校验失败/数据异常处置
     */
    public static CallType fromCode(String code) {
        for (CallType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的呼叫类型 code: " + code);
    }
}

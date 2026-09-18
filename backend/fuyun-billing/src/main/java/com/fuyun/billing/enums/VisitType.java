package com.fuyun.billing.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 就诊类型（fee_record.visit_type / settlement.settle_type 列值域，CF-3）：
 * 定长就诊号前缀语义一致（O/I + 体检预留）。枚举规范同 FeeStatus。
 */
public enum VisitType {

    /** 门诊（就诊号 O 前缀） */
    OUT("OUT"),

    /** 住院（就诊号 I 前缀，出院结算并入本型 + 费用期区分） */
    IN("IN"),

    /** 体检（PEIS 预留，P6 启用） */
    PEIS("PEIS");

    /** 存储值：DB 列写入（@EnumValue）与 JSON 输出（@JsonValue getCode）共用的业务 code */
    @EnumValue
    private final String code;

    VisitType(String code) {
        this.code = code;
    }

    /**
     * 取存储值。
     *
     * @return 业务 code（如 OUT），非空；MP 写列与 JSON 序列化均取本值
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
    public static VisitType fromCode(String code) {
        for (VisitType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的就诊类型 code: " + code);
    }
}
